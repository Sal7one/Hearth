package com.sal7one.hearth.integration

internal object OverlayPage {
    val html = """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Hearth captions</title>
<style>html,body{margin:0;background:transparent;color:white;font:32px system-ui,sans-serif}#captions{position:fixed;bottom:5vh;left:5vw;right:5vw;text-align:center;white-space:pre-wrap;overflow-wrap:anywhere;text-shadow:0 2px 4px #000}#original,#translation{display:table;margin:8px auto;padding:8px 16px;border-radius:12px;background:#101720dc;max-width:90vw}#original:empty,#translation:empty{display:none}#translation{color:#b4f4ee}#status{position:fixed;top:12px;left:16px;font-size:14px;padding:6px 10px;background:#101720dc;border-radius:6px}#status:empty{display:none}</style>
</head><body><div id="status" role="status">Connecting to Hearth…</div><main id="captions" aria-live="polite"><div id="original" dir="auto"></div><div id="translation" dir="auto"></div></main><script src="/overlay/app.js"></script></body></html>"""

    val javascript = """'use strict';
(async function(){
 const status=document.getElementById('status'), original=document.getElementById('original'), translated=document.getElementById('translation');
 const fragment=location.hash.slice(1); history.replaceState(null,'',location.pathname);
 if(fragment){
  try{const response=await fetch('/overlay/connect',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({token:fragment}),credentials:'same-origin'});if(!response.ok)throw new Error('Overlay access was rejected. Copy a fresh OBS URL from Hearth.');}
  catch(error){status.textContent=error.message;return;}
 }
 let stream=new EventSource('/overlay/events'), latest=null;
 function display(session){
  latest=session;
  const lines=(session.segments||[]).slice(-2);
  original.textContent=lines.map(s=>s.original).filter(Boolean).join('\n');
  translated.textContent=lines.map(s=>s.translation).filter(Boolean).join('\n');
  status.textContent=session.error||((session.status==='failed'||session.status==='cancelled')?session.status:'');
 }
 function reset(event){try{const sessions=JSON.parse(event.data).sessions||[];if(sessions.length)display(sessions[sessions.length-1]);else status.textContent='Waiting for captions…';}catch(error){status.textContent='Invalid Hearth event: '+error.message;}}
 stream.addEventListener('snapshot',reset);stream.addEventListener('resync',reset);
 stream.addEventListener('session',event=>{try{display(JSON.parse(event.data));}catch(error){status.textContent='Invalid Hearth event: '+error.message;}});
 stream.onopen=()=>{if(!latest)status.textContent='Waiting for captions…';};
 stream.onerror=()=>{status.textContent='Hearth disconnected. If access expired, copy a fresh OBS URL from Hearth.';};
 window.addEventListener('pagehide',()=>stream.close());
})();"""
}
