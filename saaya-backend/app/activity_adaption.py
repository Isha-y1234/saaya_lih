"""Optional interpretation using a privately served SAAYA AutoScientist checkpoint."""
import json
import os
from urllib.parse import urlsplit

import requests


def endpoint_configuration():
    endpoint = os.getenv("ACTIVITY_INFERENCE_URL", "")
    token = os.getenv("ACTIVITY_INFERENCE_TOKEN", "")
    model = os.getenv("ACTIVITY_INFERENCE_CHECKPOINT", "")
    if not (endpoint and token and model):
        return None
    try:
        url = urlsplit(endpoint)
        if (url.scheme != "https" or not url.hostname or url.username or url.password
                or url.query or url.fragment or not url.path.endswith("/v1/chat/completions")
                or url.hostname == "colab.research.google.com"
                or url.hostname.endswith(".googleusercontent.com")):
            return None
    except ValueError:
        return None
    return endpoint, token, model


def interpret(context, instructions):
    configuration = endpoint_configuration()
    if configuration is None:
        return {"status": "unavailable", "reason": "The SAAYA checkpoint endpoint is not configured."}
    endpoint, token, model = configuration
    payload = {"model": model, "response_format": {"type": "json_object"}, "max_tokens": 1024,
               "temperature": 0, "messages": [{"role": "system", "content": instructions},
                                               {"role": "user", "content": context}]}
    try:
        with requests.post(endpoint, json=payload, headers={"Authorization": f"Bearer {token}"},
                           timeout=(4, 35), allow_redirects=False, stream=True) as response:
            code = response.status_code
            if code in (401, 403):
                return {"status": "unavailable", "reason": "The SAAYA checkpoint host rejected its server credentials."}
            if code in (429, 502, 503):
                return {"status": "retry", "reason": "The SAAYA checkpoint host is busy or starting. Retry later."}
            if code != 200:
                return {"status": "unavailable", "reason": "The SAAYA checkpoint host rejected the inference request."}
            body = bytearray()
            for chunk in response.iter_content(4096):
                body.extend(chunk)
                if len(body) > 32000:
                    raise ValueError("response exceeds limit")
        envelope = json.loads(body)
        if envelope["model"] != model:
            return {"status": "unavailable", "reason": "The inference host returned a different model than the SAAYA checkpoint."}
        result = json.loads(envelope["choices"][0]["message"]["content"])
        return {"status": "ok", "result": result}
    except requests.ConnectTimeout:
        return {"status": "retry", "reason": "Could not connect to the SAAYA checkpoint host. Retry later."}
    except requests.RequestException:
        return {"status": "uncertain", "reason": "The SAAYA checkpoint request outcome could not be confirmed."}
    except (ValueError, KeyError, IndexError, TypeError):
        return {"status": "unavailable", "reason": "The SAAYA checkpoint host returned an invalid response."}
