package com.project.saaya.ui.screens

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.project.saaya.data.remote.*
import com.project.saaya.data.repository.IncidentPdf
import kotlinx.coroutines.*
import java.io.File

@Composable
fun IncidentReportsScreen(id:String,userId:String,api:ReportsApiService,onBack:()->Unit,onResources:()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var notes by remember(id,userId){mutableStateOf("")}
    var chat by remember{mutableStateOf(false)}
    var audio by remember{mutableStateOf(false)}
    var reviewed by remember{mutableStateOf(false)}
    var busy by remember{mutableStateOf(false)}
    var status by remember{mutableStateOf("Choose report contents, generate, then review the PDF before export.")}
    var reports by remember(id,userId){mutableStateOf(emptyList<ReportSummary>())}
    var report by remember(id,userId){mutableStateOf<IncidentReport?>(null)}
    var pdf by remember(id,userId){mutableStateOf<File?>(null)}
    var pageIndex by remember{mutableIntStateOf(0)}
    var pageCount by remember{mutableIntStateOf(0)}
    var bitmap by remember{mutableStateOf<Bitmap?>(null)}
    fun clear() {report=null;pdf?.delete();pdf=null;bitmap=null;reviewed=false}
    suspend fun authorize():IncidentReport {
        val current=requireNotNull(report)
        val fresh=api.read(id,current.id)
        check(fresh.incident.id==id && fresh.id==current.id)
        check(fresh==current) { "Report changed; review a fresh preview" }
        return fresh
    }
    suspend fun preview(value:IncidentReport) {
        clear()
        check(value.incident.id==id)
        val verified=api.read(id,value.id)
        val file=File(context.cacheDir,"report-pdfs/$userId/${value.id}.pdf")
        withContext(Dispatchers.IO){IncidentPdf.generate(verified,file)}
        report=verified;pdf=file;pageIndex=0;reviewed=false
        status="Review all pages. Statements and AI observations are unverified."
    }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")){uri->
        if(uri!=null) scope.launch {
            busy=true
            try {
                authorize()
                val source=requireNotNull(pdf)
                withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri,"w").use {output->
                    requireNotNull(output);source.inputStream().use {it.copyTo(output)}
                }}
                status="PDF exported. Saaya cannot revoke this external copy."
            } catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){clear();status="Export denied or unavailable. Generate and authorize the report again."}
            finally{busy=false}
        }
    }
    LaunchedEffect(id,userId){try{reports=api.list(id).reports}catch(_:Exception){status="Reports unavailable for this session."}}
    LaunchedEffect(pdf,pageIndex){
        val file=pdf?:return@LaunchedEffect
        try {
            bitmap=withContext(Dispatchers.IO){
                PdfRenderer(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)).use {renderer->
                    pageCount=renderer.pageCount
                    renderer.openPage(pageIndex.coerceIn(0,renderer.pageCount-1)).use {page->
                        Bitmap.createBitmap(page.width,page.height,Bitmap.Config.ARGB_8888).also {it.eraseColor(android.graphics.Color.WHITE);page.render(it,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)}
                    }
                }
            }
        } catch(_:Exception){bitmap=null;status="PDF preview unavailable. Retry generation."}
    }
    DisposableEffect(lifecycle,report?.id){
        val observer=LifecycleEventObserver{_,event->
            if(event==Lifecycle.Event.ON_RESUME && report!=null) scope.launch {
                try{authorize()}catch(_:Exception){clear();status="Report authorization expired or access was revoked."}
            }
        }
        lifecycle.addObserver(observer)
        onDispose{lifecycle.removeObserver(observer)}
    }
    DisposableEffect(id,userId){onDispose{pdf?.delete()}}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        TextButton(onClick=onBack){Text("Back")}
        Text("Incident Reports",style=MaterialTheme.typography.headlineSmall)
        TextButton(onClick=onResources){Text("Legal & Emergency Resources")}
        Text("Incident $id")
        Text("User-generated summary, not an official police report, forensic certification or legal finding. No guaranteed legal outcome or admissibility.")
        Row{Checkbox(chat,{chat=it},enabled=!busy);Text("Include authorized chat excerpts (unverified statements)")}
        Row{Checkbox(audio,{audio=it},enabled=!busy);Text("Include consented audio transcript, only if verified audio is enabled")}
        OutlinedTextField(notes,{notes=it.take(2000)},label={Text("User-provided report notes")},enabled=!busy)
        Text(status)
        if(busy)CircularProgressIndicator()
        Button(enabled=!busy,onClick={scope.launch{
            busy=true
            try{preview(api.create(id,ReportOptions(chat,audio,notes)));reports=api.list(id).reports}
            catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){clear();status="Report unavailable. Check authorization and connection."}
            finally{busy=false}
        }}){Text("Generate and preview report")}
        TextButton(enabled=!busy,onClick={scope.launch{try{reports=api.list(id).reports}catch(_:Exception){clear();reports=emptyList();status="Reports unavailable."}}}){Text("Refresh saved reports")}
        reports.forEach{item->TextButton(enabled=!busy,onClick={scope.launch{
            busy=true
            try{preview(api.read(id,item.id))}catch(_:Exception){clear();status="Report unavailable for this account."}finally{busy=false}
        }}){Text("Review ${item.generatedAt}")}}
        bitmap?.let{Image(it.asImageBitmap(),"Report PDF page ${pageIndex+1}",Modifier.fillMaxWidth().height(550.dp))}
        if(pdf!=null){
            Text("Page ${pageIndex+1} of $pageCount")
            Row{TextButton(enabled=pageIndex>0,onClick={pageIndex--}){Text("Previous page")};TextButton(enabled=pageIndex+1<pageCount,onClick={pageIndex++}){Text("Next page")}}
            Row{Checkbox(reviewed,{reviewed=it});Text("I reviewed this report and understand that exported/shared copies cannot be revoked by Saaya.")}
            Button(enabled=reviewed&&!busy,onClick={scope.launch{
                try{authorize();export.launch("Saaya-$id.pdf")}catch(_:Exception){clear();status="Access denied. Export cancelled."}
            }}){Text("Export PDF")}
            Button(enabled=reviewed&&!busy,onClick={scope.launch{
                try{
                    authorize()
                    val uri=FileProvider.getUriForFile(context,"${context.packageName}.evidence",requireNotNull(pdf))
                    val intent=Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM,uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply{clipData=ClipData.newRawUri("Saaya report",uri)}
                    context.startActivity(Intent.createChooser(intent,"Share reviewed report"))
                }catch(_:Exception){clear();status="Access denied or sharing unavailable."}
            }}){Text("Share reviewed PDF")}
        }
    }
}
