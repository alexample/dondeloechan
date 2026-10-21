/* Tema «teletexto» de la web: portada de página con banda azul y caja amarilla, índice de cifras,
   línea de próximos partidos que va rotando y barra Fastext de colores. Solo lee los datos de la página. */
(function(){
  "use strict";
  var PAG={0:"101",1:"201",2:"211",5:"221",3:"231",4:"241"};
  var esc=function(s){return String(s==null?"":s).replace(/[&<>"]/g,function(c){return {"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]})};
  var TZ="Europe/Madrid";
  var fHora=new Intl.DateTimeFormat("es-ES",{timeZone:TZ,hour:"2-digit",minute:"2-digit"});
  var fDiaC=new Intl.DateTimeFormat("es-ES",{timeZone:TZ,weekday:"short",day:"numeric"});
  var fCab=new Intl.DateTimeFormat("es-ES",{timeZone:TZ,weekday:"short",day:"2-digit",month:"short"});
  var fReloj=new Intl.DateTimeFormat("es-ES",{timeZone:TZ,hour:"2-digit",minute:"2-digit",second:"2-digit"});
  var fClave=new Intl.DateTimeFormat("en-CA",{timeZone:TZ,year:"numeric",month:"2-digit",day:"2-digit"});
  var fNum=new Intl.DateTimeFormat("es-ES",{timeZone:TZ,day:"2-digit",month:"2-digit",year:"numeric"});
  var cap=function(s){return s.charAt(0).toUpperCase()+s.slice(1)};
  var limpia=function(s){return s.replace(/\./g,"")};

  function lista(){
    if(typeof matches==="undefined"||!matches)return [];
    return league===0?matches.filter(function(m){return FAV.has(m.localId)||FAV.has(m.visitanteId)}):matches.filter(function(m){return m.liga===league});
  }
  function canalCorto(m){
    var c=m.canales||[];if(!c.length)return m.sinTV?"SIN TV":"CANAL POR CONFIRMAR";
    var k=chOf(c[0]);return k.name+(k.dial&&k.dial!=="TDT"?" "+k.dial:"");
  }
  function temporada(){var d=new Date(),y=+fClave.format(d).slice(0,4),mth=+fClave.format(d).slice(5,7);return mth>=7?y+"/"+(y+1):(y-1)+"/"+y}
  var prox=[],iProx=0;
  function lineaFlash(){
    var el=document.getElementById("tt-flash");if(!el)return;
    if(!prox.length){el.innerHTML="<em>SIN PARTIDOS PRÓXIMOS</em>";return}
    var m=prox[iProx%prox.length],now=Date.now(),st=status(m,now),d=new Date(m.inicio);
    var h=teamOf(m.localId,m.local,m.localAbbr),a=teamOf(m.visitanteId,m.visitante,m.visitanteAbbr);
    el.innerHTML=(st==="live"?'<span class="vivo">EN JUEGO</span>':'<em>'+esc(limpia(cap(fDiaC.format(d))))+' '+(m.horaPendiente?"--:--":fHora.format(d))+'h</em>')+
      '<span>'+esc(h.abbr)+'-'+esc(a.abbr)+' '+esc(canalCorto(m))+'</span><small>'+((iProx%prox.length)+1)+'/'+prox.length+'</small>';
  }
  function portada(){
    var now=Date.now(),all=lista(),hoy=fClave.format(new Date(now));
    var pend=all.filter(function(m){var s=status(m,now);return s!=="done"&&s!=="off"});
    var fijo=pend.filter(function(m){return (m.canales||[]).length&&!m.provisional});
    var vivos=all.filter(function(m){return status(m,now)==="live"});
    var deHoy=all.filter(function(m){return fClave.format(new Date(m.inicio))===hoy});
    prox=pend.slice().sort(function(a,b){return new Date(a.inicio)-new Date(b.inicio)}).slice(0,12);
    var jors=[...new Set(pend.map(function(m){return m.jornada}))].filter(function(j){return j&&j!=="Partido aplazado"});
    var j=league===0?(FAV.size+" EQUIPO"+(FAV.size===1?"":"S")):jors.length?(/^\d+$/.test(String(jors[0]))?"JORNADA "+jors[0]:String(jors[0]).toUpperCase()):"";
    var primero=pend[0]?fNum.format(new Date(pend[0].inicio)).replace(/\//g,"-"):"";
    var titulo=league===0?"MIS EQUIPOS":"FÚTBOL:"+((COMP[league]||{}).name||"").replace(/^LALIGA /,"LIGA ");
    return '<section class="tt" id="t-hero" aria-label="Portada">'+
      '<div class="tt-linea"><span>P'+PAG[league]+'</span><span>DLE '+PAG[league]+'</span><span>'+esc(limpia(cap(fCab.format(new Date()))))+'</span><b id="t-reloj">'+fReloj.format(new Date())+'</b></div>'+
      '<div class="tt-banda"><div class="tt-logo">DLE<br><i>TV</i></div><div class="tt-der"><div class="tt-caja"><b>'+esc(titulo)+'</b><span>TEMPORADA '+temporada()+'</span></div></div></div>'+
      '<div class="tt-jor"><b>'+esc(j)+'</b>'+(primero?'<span>'+primero+'</span>':'')+'</div>'+
      '<div class="tt-idx">'+
        '<div class="r"><em>Partidos por jugar</em><b>'+pend.length+'</b></div>'+
        '<div class="a"><em>Con canal confirmado</em><b>'+fijo.length+'</b></div>'+
        '<div class="c"><em>'+(vivos.length?'En juego ahora':'Partidos de hoy')+'</em><b>'+(vivos.length||deHoy.length)+'</b></div>'+
      '</div>'+
      '<div class="tt-flash" id="tt-flash" aria-live="off"></div>'+
    '</section>';
  }
  function fastext(){
    if(document.querySelector(".tt-fast"))return;
    var f=document.createElement("nav");f.className="tt-fast";f.setAttribute("aria-label","Competiciones");
    f.innerHTML='<button class="r" data-l="1">Primera</button><button class="v" data-l="2">Segunda</button><button class="a" data-l="5">1ª RFEF</button><button class="c" data-l="4">Champions</button>';
    f.addEventListener("click",function(e){var b=e.target.closest("button");if(!b)return;var t=document.querySelector('.tabs button[data-l="'+b.dataset.l+'"]');if(t)t.click()});
    document.body.appendChild(f);
  }
  function colocar(){
    var app=document.getElementById("app");if(!app||typeof matches==="undefined"||!matches)return;
    var viejo=document.getElementById("t-hero");if(viejo)viejo.remove();
    var tmp=document.createElement("div");tmp.innerHTML=portada();var hero=tmp.firstChild;
    var ahora=document.getElementById("ahora"),sec=ahora&&ahora.closest(".day");
    if(sec&&!(typeof query!=="undefined"&&query.trim()))sec.parentNode.insertBefore(hero,sec);else app.insertBefore(hero,app.firstChild);
    iProx=0;lineaFlash();
    // Como en el teletexto: "21:00h" en una sola línea.
    document.querySelectorAll("#app .kick").forEach(function(k){var sp=k.querySelector("span"),b=k.querySelector("b");
      if(sp&&b&&sp.textContent==="h"&&/^\d\d:\d\d$/.test(b.textContent)){b.textContent+="h";sp.remove()}});
  }
  document.querySelectorAll(".tabs button").forEach(function(b){b.setAttribute("data-p",PAG[+b.dataset.l])});
  var renderOriginal=window.render;
  window.render=function(){var r=renderOriginal.apply(this,arguments);try{colocar()}catch(e){}return r};
  var objetivoOriginal=window.objetivoAhora;
  window.objetivoAhora=function(){var h=document.getElementById("t-hero"),t=objetivoOriginal.apply(this,arguments);
    if(h&&t&&h.nextElementSibling&&(h.nextElementSibling===t||h.nextElementSibling.contains(t)))return h;return t};
  setInterval(function(){var r=document.getElementById("t-reloj");if(r)r.textContent=fReloj.format(new Date())},1000);
  setInterval(function(){iProx++;lineaFlash()},4000);
  var q=document.getElementById("q");if(q)q.placeholder="Busca tu equipo";
  fastext();
  try{colocar()}catch(e){}
})();
