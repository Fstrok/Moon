package app.moon.security

import org.json.JSONObject

/** JS для страницы входа сайта: перехват логина/пароля и автозаполнение. Работает с любой формой с полем пароля. */
object LoginScripts {
    private const val HELPERS = """
        function __moonUser(f){
          var c=(f||document).querySelectorAll('input');
          for(var i=0;i<c.length;i++){var t=(c[i].type||'text').toLowerCase();var n=((c[i].name||'')+' '+(c[i].id||'')).toLowerCase();
            if((t=='text'||t=='email')&&/user|login|name|mail|nick/.test(n))return c[i];}
          for(var j=0;j<c.length;j++){var t2=(c[j].type||'text').toLowerCase();if(t2=='text'||t2=='email')return c[j];}
          return null;}
        function __moonSet(el,v){if(!el)return;el.focus&&el.focus();el.value=v;
          el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}
    """

    const val BRIDGE = "MoonBridge"

    /** Отправляет в MoonBridge.onCredentials(login, pass) то, что пользователь ввёл в форму входа. */
    val capture = """
        (function(){
          if(window.__moonHooked)return; window.__moonHooked=true;
          $HELPERS
          function grab(f){try{if(!f)return;var p=f.querySelector('input[type=password]');if(!p||!p.value)return;
            var u=__moonUser(f);$BRIDGE.onCredentials(u?u.value:'',p.value);}catch(e){}}
          document.addEventListener('submit',function(e){grab(e.target);},true);
          document.addEventListener('click',function(e){var b=e.target&&e.target.closest&&e.target.closest('button,input[type=submit],input[type=image]');
            if(b)grab(b.form||b.closest('form'));},true);
          document.addEventListener('keydown',function(e){if(e.key==='Enter'&&e.target&&e.target.form)grab(e.target.form);},true);
          var pw=document.querySelector('input[type=password]');
          if(pw&&pw.offsetParent!==null){try{pw.scrollIntoView({block:'center'});}catch(e){}}
        })();
    """.trimIndent()

    /** Заполняет форму и отправляет. Возвращает 'ok' | 'noform'. */
    fun fill(c: Credentials): String = """
        (function(l,p){
          $HELPERS
          var pw=document.querySelector('input[type=password]'); if(!pw)return 'noform';
          var f=pw.form||pw.closest('form'); var u=__moonUser(f||document);
          __moonSet(u,l); __moonSet(pw,p);
          if(f){var r=f.querySelector('input[type=checkbox][name*=remember],input[type=checkbox][name*=save],input[type=checkbox][id*=remember]'); if(r)r.checked=true;}
          var b=f?f.querySelector('input[type=submit],button[type=submit],button:not([type]),input[type=image]'):null;
          if(f&&f.requestSubmit){try{if(b)f.requestSubmit(b);else f.requestSubmit();return 'ok';}catch(e){}}
          if(b){b.click();return 'ok';}
          if(f){f.submit();return 'ok';}
          return 'noform';
        })(${JSONObject.quote(c.login)},${JSONObject.quote(c.password)});
    """.trimIndent()
}
