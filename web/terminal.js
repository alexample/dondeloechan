/* Tema «terminal» de la web: panel de cifras encima de los partidos de hoy, reloj, cinta de próximos partidos
   y contadores en las pestañas. Solo lee los datos de la página; no cambia cómo funciona. */
(function(){
  "use strict";
  var NOMBRE={0:"Mis equipos",1:"Primera",2:"Segunda",5:"1ª RFEF",3:"Copa",4:"Champions"};
  var RUTA={0:"mis-equipos",1:"laliga",2:"hypermotion",5:"primera-rfef",3:"copa-del-rey",4:"champions"};
  var esc=function(s){return String(s==null?"":s).replace(/[&<>"]/g,function(c){return {"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]})};
  var fHora=new Intl.DateTimeFormat("es-ES",{timeZone:"Europe/Madrid",hour:"2-digit",minute:"2-digit"});
  var fDia=new Intl.DateTimeFormat("es-ES",{timeZone:"Europe/Madrid",weekday:"short"});
  var fReloj=new Intl.DateTimeFormat("es-ES",{timeZone:"Europe/Madrid",hour:"2-digit",minute:"2-digit",second:"2-digit"});
  var fClave=new Intl.DateTimeFormat("en-CA",{timeZone:"Europe/Madrid",year:"numeric",month:"2-digit",day:"2-digit"});

  function circuito(){
    return '<svg class="circ" viewBox="0 0 46 300" preserveAspectRatio="none" aria-hidden="true" fill="none" stroke-width="1.2">'+
      '<path d="M40 0v40l-22 22v70" stroke="#1d3a2b"/><path d="M28 60v40l-14 14v80l14 14v40" stroke="#16301f"/>'+
      '<path d="M44 150h-16l-10 10v60" stroke="#1b3550"/><path d="M8 230v70" stroke="#1d3a2b"/>'+
      '<rect x="14" y="128" width="8" height="8" stroke="#4ade80" opacity=".7"/>'+
      '<rect x="4" y="44" width="8" height="8" stroke="#f472b6" opacity=".7"/>'+
      '<rect x="14" y="216" width="8" height="8" stroke="#38bdf8" opacity=".75"/>'+
      '<rect x="24" y="268" width="8" height="8" stroke="#a78bfa" opacity=".7"/>'+
      '<circle cx="30" cy="96" r="2.5" stroke="#2d5a40"/><circle cx="10" cy="180" r="2.5" stroke="#2d5a40"/></svg>';
  }
  function barra(v,max){var n=12,on=max>0?Math.round(n*Math.min(v,max)/max):0,s="";for(var i=0;i<n;i++)s+='<i'+(i<on?' class="on"':'')+'></i>';return '<span class="t-bar" aria-hidden="true">'+s+'</span>'}
  function lista(){
    if(typeof matches==="undefined"||!matches)return [];
    return league===0?matches.filter(function(m){return FAV.has(m.localId)||FAV.has(m.visitanteId)}):matches.filter(function(m){return m.liga===league});
  }
  function canalCorto(m){
    var c=m.canales||[];if(!c.length)return m.sinTV?"sin TV":"canal por confirmar";
    var k=chOf(c[0]);return k.name+(k.dial&&k.dial!=="TDT"?" "+k.dial:k.dial==="TDT"?" (TDT)":"")+(m.provisional?" ·prov.":"");
  }
  function heroHTML(){
    var now=Date.now(),all=lista(),hoy=fClave.format(new Date(now));
    var pend=all.filter(function(m){var s=status(m,now);return s!=="done"&&s!=="off"});
    var fijo=pend.filter(function(m){return (m.canales||[]).length&&!m.provisional});
    var vivos=all.filter(function(m){return status(m,now)==="live"});
    var deHoy=all.filter(function(m){return fClave.format(new Date(m.inicio))===hoy});
    var tercero=vivos.length?{n:vivos.length,t:"en juego",c:"c vivo"}:{n:deHoy.length,t:"hoy",c:"c"};
    var prox=(pend.length?pend:matches.filter(function(m){var s=status(m,now);return s!=="done"&&s!=="off"}))
      .slice().sort(function(a,b){return new Date(a.inicio)-new Date(b.inicio)}).slice(0,10);
    var cinta=prox.map(function(m){
      var h=teamOf(m.localId,m.local,m.localAbbr),a=teamOf(m.visitanteId,m.visitante,m.visitanteAbbr),d=new Date(m.inicio),st=status(m,now);
      var cuando=st==="live"?(m.minuto||"EN JUEGO"):(fClave.format(d)===hoy?"HOY":fDia.format(d).replace(".","").toUpperCase())+" "+(m.horaPendiente?"--:--":fHora.format(d));
      return '<span'+(st==="live"?' class="vivo"':'')+'>'+esc(cuando)+' <em>'+esc(h.abbr)+'–'+esc(a.abbr)+'</em> → <b>'+esc(canalCorto(m))+'</b></span>';
    }).join("");
    var desc=league===0?"Los partidos de tus equipos, de cualquier competición: día, hora y canal exacto en un solo panel."
      :"Día, hora y canal exacto de cada partido de "+esc((COMP[league]||{}).name||"")+": Movistar Plus+, DAZN, Orange y TDT, en un solo panel.";
    return '<section class="t-hero" id="t-hero" aria-label="Resumen de '+esc(NOMBRE[league])+'">'+circuito().replace('class="circ"','class="circ l"')+circuito().replace('class="circ"','class="circ r"')+
      '<div class="t-pill"><i>&gt;_</i>GUÍA TV<span class="sep">|</span><span class="dot"></span><span class="reloj" id="t-reloj">'+fReloj.format(new Date())+'</span></div>'+
      '<h2 class="t-tit">'+esc(NOMBRE[league])+'</h2>'+
      '<p class="t-cmd">dle@guia <b>:</b> ~/'+RUTA[league]+' <b>$</b> <em>canal --exacto</em><span class="cur"></span></p>'+
      '<p class="t-desc">'+desc+'</p>'+
      '<div class="t-nums">'+
        '<div class="t-num"><b>'+pend.length+'</b>'+barra(pend.length,pend.length)+'<small>:: partidos</small></div>'+
        '<div class="t-num b"><b>'+fijo.length+'</b>'+barra(fijo.length,pend.length)+'<small>:: canal fijo</small></div>'+
        '<div class="t-num '+tercero.c+'"><b>'+tercero.n+'</b>'+barra(tercero.n,pend.length)+'<small>:: '+tercero.t+'</small></div>'+
      '</div>'+
      (cinta?'<div class="t-tick" aria-label="Próximos partidos"><div class="cinta" style="--dur:'+Math.max(25,prox.length*6)+'s">'+cinta+cinta+'</div></div>':'')+
    '</section>';
  }
  function contarPestanas(){
    if(typeof matches==="undefined"||!matches)return;
    var now=Date.now();
    document.querySelectorAll(".tabs button").forEach(function(b){
      var l=+b.dataset.l,n=matches.filter(function(m){var s=status(m,now);if(s==="done"||s==="off")return false;return l===0?(FAV.has(m.localId)||FAV.has(m.visitanteId)):m.liga===l}).length;
      if(l===0&&!FAV.size)b.removeAttribute("data-n");else b.setAttribute("data-n",n);
    });
  }
  function colocar(){
    var app=document.getElementById("app");if(!app||typeof matches==="undefined"||!matches)return;
    var viejo=document.getElementById("t-hero");if(viejo)viejo.remove();
    var tmp=document.createElement("div");tmp.innerHTML=heroHTML();var hero=tmp.firstChild;
    var ahora=document.getElementById("ahora"),sec=ahora&&ahora.closest(".day");
    if(sec&&!(typeof query!=="undefined"&&query.trim()))sec.parentNode.insertBefore(hero,sec);else app.insertBefore(hero,app.firstChild);
    contarPestanas();
  }
  // Envolver las funciones de la página (son globales): tras cada render, el panel; y "hoy" apunta al panel.
  var renderOriginal=window.render;
  window.render=function(){var r=renderOriginal.apply(this,arguments);try{colocar()}catch(e){}return r};
  var objetivoOriginal=window.objetivoAhora;
  window.objetivoAhora=function(){var h=document.getElementById("t-hero"),t=objetivoOriginal.apply(this,arguments);
    if(h&&t&&h.nextElementSibling&&(h.nextElementSibling===t||h.nextElementSibling.contains(t)))return h;return t};
  setInterval(function(){var r=document.getElementById("t-reloj");if(r)r.textContent=fReloj.format(new Date())},1000);
  var q=document.getElementById("q");if(q)q.placeholder="Buscar equipo...";
  try{colocar()}catch(e){}
})();
