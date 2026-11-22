import test from 'node:test';
import assert from 'node:assert/strict';
import {handle, models, validateWav, validateJpeg, transcript} from './worker.mjs';
const env={AI_WORKER_SECRET:'fixture-only-shared-secret',FREE_PLAN_CONFIRMED:'true',
  WHISPER_WAV_VERIFIED:'true',B2_HOST:'s3.us-west-004.backblazeb2.com'};
const url='https://s3.us-west-004.backblazeb2.com/saaya-evidence/analysis/fixture?X-Amz-Signature=fixture';
function req(data, auth=true) { return new Request('https://fixture.workers.dev',{method:'POST',headers:{
  Authorization:auth?'Bearer '+env.AI_WORKER_SECRET:'invalid','X-Cf-Account':'a'.repeat(32),'X-Cf-Token':'fixture-only'},body:JSON.stringify(data)}); }
test('unauthorized request and disabled free setup never fetch',async()=>{
  const never=()=>assert.fail('must not call external services');
  assert.equal((await handle(req({},false),env,never)).status,403);
  assert.equal((await (await handle(req({}),{...env,FREE_PLAN_CONFIRMED:'false'},never)).json()).status,'unavailable');
});
test('only allowlisted models and private B2 host/path accepted',async()=>{
  const never=()=>assert.fail('must not fetch untrusted URLs');
  for(const data of [{kind:'paid',model:'paid'}, {kind:'frame',model:models.frame,url:'https://example.com/public.jpg'},
    {kind:'frame',model:models.frame,url:'https://s3.us-west-004.backblazeb2.com/saaya-evidence/evidence/original'}]) {
    assert.equal((await (await handle(req(data),env,never)).json()).status,'unavailable');
  }
});
function wav(pcmBytes=32000, silent=false) {
  const bytes=new Uint8Array(pcmBytes+44),v=new DataView(bytes.buffer);
  const str=(offset,s)=>bytes.set(new TextEncoder().encode(s),offset);
  str(0,'RIFF');str(8,'WAVE');str(12,'fmt ');str(36,'data');
  v.setUint32(4,bytes.length-8,true);v.setUint32(16,16,true);v.setUint16(20,1,true);v.setUint16(22,1,true);v.setUint32(24,16000,true);
  v.setUint32(28,32000,true);v.setUint16(32,2,true);v.setUint16(34,16,true);v.setUint32(40,pcmBytes,true);
  if(pcmBytes>=2 && !silent)v.setInt16(44,1000,true);
  return bytes;
}
test('exact PCM WAV structure required, compressed audio rejected',()=>{
  const bytes=wav(),v=new DataView(bytes.buffer);
  assert.doesNotThrow(()=>validateWav(bytes));v.setUint16(20,3,true);
  assert.throws(()=>validateWav(bytes)); assert.throws(()=>validateWav(new Uint8Array(50)));
});
test('WAV limits, alignment and silent/empty audio rejected',()=>{
  assert.equal(validateWav(wav(960000)),30);
  for(const bytes of [wav(0),wav(960002),wav(32001),wav(32000,true)])assert.throws(()=>validateWav(bytes));
  for(const [offset,value] of [[4,1],[24,48000],[28,16000],[40,1]]) {
    const bytes=wav();new DataView(bytes.buffer).setUint32(offset,value,true);assert.throws(()=>validateWav(bytes));
  }
  for(const [offset,value] of [[20,3],[22,2],[32,4],[34,8]]) {
    const bytes=wav();new DataView(bytes.buffer).setUint16(offset,value,true);assert.throws(()=>validateWav(bytes));
  }
});
test('transcription trims text, validates word times, does not fabricate metadata',()=>{
  const result=transcript({text:' hello ',words:[null,17,{word:'hello',start:0,end:0.4},{word:'bad',start:-1,end:0.1},{word:'beyond',start:0,end:5}]},1);
  assert.equal(result.text,'hello');assert.equal(result.words.length,1);assert.equal(result.language,null);
  assert.throws(()=>transcript({text:'   '},1));
  assert.equal(transcript({text:'नमस्ते'},1).words.length,0);
});
async function audioTask(overrides={},environment=env, answer={success:true,result:{text:'hello',words:[{word:'hello',start:0,end:0.5}]}}) {
  const bytes=wav();const sha256=Buffer.from(await crypto.subtle.digest('SHA-256',bytes)).toString('hex');
  const data={kind:'audio',model:models.audio,url:url+'&versionId=pinned',versionId:'pinned',size:bytes.length,sha256,...overrides};
  const calls=[];
  const response=await handle(req(data),environment,async(target,options)=>{
    calls.push({target:String(target),options});
    return calls.length===1?new Response(bytes,{headers:{'x-amz-version-id':'pinned'}}):Response.json(answer);
  });
  return {value:await response.json(),calls,bytes};
}
test('original Whisper receives exact binary WAV and audio/wav, never JSON or strings',async()=>{
  const {value,calls,bytes}=await audioTask();assert.equal(value.status,'ok');assert.equal(calls.length,2);
  assert.match(calls[1].target,/@cf\/openai\/whisper$/);
  assert.equal(calls[1].options.headers['Content-Type'],'audio/wav');
  assert.deepEqual(calls[1].options.body,bytes);assert.ok(calls[1].options.body instanceof Uint8Array);
});
test('B2 version mismatch and SHA mismatch cannot reach inference',async()=>{
  for(const override of [{versionId:'wrong'},{sha256:'0'.repeat(64)}]){
    const {value,calls}=await audioTask(override);assert.equal(value.status,'unavailable');assert.ok(calls.length<=1);
  }
});
test('B2 response version mismatch is rejected before model invocation',async()=>{
  const bytes=wav();const sha256=Buffer.from(await crypto.subtle.digest('SHA-256',bytes)).toString('hex');let calls=0;
  const result=await handle(req({kind:'audio',model:models.audio,url:url+'&versionId=pinned',versionId:'pinned',size:bytes.length,sha256}),env,
    async()=>{calls++;return new Response(bytes,{headers:{'x-amz-version-id':'wrong-version'}});});
  assert.equal((await result.json()).status,'unavailable');assert.equal(calls,1);
});
test('audio stays gated; explicit verification gate permits only marked samples',async()=>{
  const disabled={...env,WHISPER_WAV_VERIFIED:'false',ALLOW_AUDIO_VERIFICATION:'false'};
  assert.equal((await audioTask({},disabled)).value.status,'unavailable');
  assert.equal((await audioTask({verificationOnly:true},disabled)).value.status,'unavailable');
  assert.equal((await audioTask({verificationOnly:true},{...disabled,ALLOW_AUDIO_VERIFICATION:'true'})).value.status,'ok');
  assert.equal((await audioTask({},{...disabled,ALLOW_AUDIO_VERIFICATION:'true'})).value.status,'unavailable');
});
test('failed and empty Whisper responses never become fabricated transcripts',async()=>{
  assert.equal((await audioTask({},env,{success:false,errors:[{code:5004}]})).value.status,'uncertain');
  assert.equal((await audioTask({},env,{success:true,result:{text:''}})).value.status,'unavailable');
});
test('empty, silent, oversized and unsupported WAV bodies never invoke AI',async()=>{
  const compressed=wav();new DataView(compressed.buffer).setUint16(20,3,true);
  for(const bytes of [wav(0),wav(32000,true),wav(960002),compressed]) {
    const sha256=Buffer.from(await crypto.subtle.digest('SHA-256',bytes)).toString('hex');let calls=0;
    const result=await handle(req({kind:'audio',model:models.audio,url:url+'&versionId=pinned',versionId:'pinned',size:bytes.length,sha256}),env,
      async()=>{calls++;return new Response(bytes,{headers:{'x-amz-version-id':'pinned'}});});
    assert.equal((await result.json()).status,'unavailable');assert.equal(calls,1);
  }
});
const jpeg=Uint8Array.from([255,216,255,192,0,11,8,0,100,0,100,1,1,17,0,255,217]);
test('JPEG dimensions bounded',()=>{
  assert.doesNotThrow(()=>validateJpeg(jpeg)); const huge=jpeg.slice();huge[9]=10;
  assert.throws(()=>validateJpeg(huge));
});
test('expired evidence never invokes inference; output sanitized',async()=>{
  let calls=0;
  const result=await handle(req({kind:'frame',model:models.frame,url,size:jpeg.length,sha256:'a'.repeat(64)}),env,
    async()=>{calls++;return new Response('private upstream message',{status:403});});
  assert.equal(calls,1);assert.equal((await result.json()).status,'unavailable');
});
test('quota exhaustion and uncertain timeout have no retry or fallback',async()=>{
  const data={kind:'report',model:models.report,context:'non-sensitive fixture'};
  let calls=0;
  let result=await handle(req(data),env,async()=>{calls++;return Response.json({success:false,errors:[{code:3036}]},{status:429});});
  assert.equal((await result.json()).status,'quota');assert.equal(calls,1);
  result=await handle(req(data),env,async()=>{calls++;throw Error('private credentials must never leak');});
  const body=await result.text();assert.match(body,/uncertain/);assert.doesNotMatch(body,/credentials/);assert.equal(calls,2);
});
test('bounded frame bytes sent directly from B2 to exact model',async()=>{
  const hash=Buffer.from(await crypto.subtle.digest('SHA-256',jpeg)).toString('hex');
  const calls=[];
  const result=await handle(req({kind:'frame',model:models.frame,url,size:jpeg.length,sha256:hash}),env,async(target,options)=>{
    calls.push(String(target));
    if(calls.length===1)return new Response(jpeg);
    const input=JSON.parse(options.body);assert.deepEqual(input.image,Array.from(jpeg));assert.equal(input.max_tokens,256);
    return Response.json({success:true,result:{response:JSON.stringify({observations:[],interpretations:[],uncertainties:[],evidenceReferences:[]}),usage:{prompt_tokens:100,completion_tokens:10}}});
  });
  assert.equal((await result.json()).status,'ok');assert.equal(calls.length,2);assert.match(calls[1],/llama-3.2-11b-vision-instruct$/);
});
test('local Activity frame reaches existing vision model without B2', async () => {
  const data = {kind:'frame', model:models.frame, size:jpeg.length, media:Buffer.from(jpeg).toString('base64'),
    sha256:Buffer.from(await crypto.subtle.digest('SHA-256',jpeg)).toString('hex')};
  const request = req(data);
  const local = new Request('https://fixture.workers.dev/activity-local', {method:'POST', headers:request.headers, body:JSON.stringify(data)});
  let calls = 0;
  const result = await handle(local, env, async (target, options) => {
    calls++;
    assert.match(String(target), /api\.cloudflare\.com.*llama-3\.2-11b-vision-instruct$/);
    assert.deepEqual(JSON.parse(options.body).image, Array.from(jpeg));
    return Response.json({success:true,result:{response:JSON.stringify({observations:[],interpretations:[],uncertainties:[],evidenceReferences:[]})}});
  });
  assert.equal((await result.json()).status, 'ok');
  assert.equal(calls, 1);
});
test('local Activity WAV uses the existing Whisper binary contract without B2', async () => {
  const bytes = wav();
  const data = {kind:'audio', model:models.audio, size:bytes.length, media:Buffer.from(bytes).toString('base64'),
    sha256:Buffer.from(await crypto.subtle.digest('SHA-256',bytes)).toString('hex')};
  const local = new Request('https://fixture.workers.dev/activity-local', {method:'POST', headers:req(data).headers, body:JSON.stringify(data)});
  let calls = 0;
  const result = await handle(local, {...env, WHISPER_WAV_VERIFIED:'true'}, async (target, options) => {
    calls++;
    assert.match(String(target), /api\.cloudflare\.com.*\/whisper$/);
    assert.equal(options.headers['Content-Type'], 'audio/wav');
    assert.deepEqual(Buffer.from(options.body), Buffer.from(bytes));
    return Response.json({success:true,result:{text:'fixture speech'}});
  });
  assert.equal((await result.json()).result.text, 'fixture speech');
  assert.equal(calls, 1);
});

test('local Activity report sends only supplied source context to Cloudflare synthesis', async () => {
  const context = JSON.stringify({kind:'video',missingCoverage:true,sources:[
    {id:'note',kind:'note',text:'Synthetic user statement.',offsetMs:null},
    {id:'source-01',kind:'vision',text:'Two figures are visible.',offsetMs:2400},
  ]});
  const data = {kind:'report',model:models.report,context};
  const local = new Request('https://fixture.workers.dev/activity-local', {method:'POST', headers:req(data).headers, body:JSON.stringify(data)});
  const report = {observations:['Two figures are visible.'],interpretations:[],
    uncertainties:['Audio was unavailable.'],evidenceReferences:['source-01']};
  let calls = 0;
  const result = await handle(local, env, async (target, options) => {
    calls++;
    assert.match(String(target), /api\.cloudflare\.com.*llama-3\.2-3b-instruct$/);
    const input = JSON.parse(options.body);
    assert.match(input.messages[0].content, /single local Activity capture.*supplied source IDs/);
    assert.equal(input.messages[1].content, context);
    return Response.json({success:true,result:{response:JSON.stringify(report)}});
  });
  assert.equal(calls, 1);
  assert.deepEqual((await result.json()).result, {...report,confidence:'unverified'});
});

test('local Activity can retry invalid completed responses but preserves unknown outcomes', async () => {
  const data = {kind:'report',model:models.report,context:'synthetic fixture'};
  const local = () => new Request('https://fixture.workers.dev/activity-local',
    {method:'POST',headers:req(data).headers,body:JSON.stringify(data)});
  const invalid = await handle(local(),env,async()=>Response.json({success:true,result:{response:'invalid'}}));
  assert.equal((await invalid.json()).status,'unavailable');
  const lost = await handle(local(),env,async()=>{throw Error('synthetic timeout');});
  assert.equal((await lost.json()).status,'uncertain');
});
