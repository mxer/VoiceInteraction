package com.github.unktomi;
import java.util.*;
import java.io.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import net.sf.saxon.dom.DocumentWrapper;
import net.sf.saxon.query.DynamicQueryContext;
import net.sf.saxon.query.StaticQueryContext;
import net.sf.saxon.query.XQueryExpression;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;

public abstract class GRXMLSpeechRecognizer {

  private Scriptable scope;
  private XQueryExpression grxml2js;
  private final net.sf.saxon.Configuration config = net.sf.saxon.Configuration.newConfiguration();
  private final Map<String, Scriptable> grammarScopes = new HashMap();
  private final DocumentBuilderFactory fac = DocumentBuilderFactory.newInstance();


  public GRXMLSpeechRecognizer() {}

  abstract protected InputStream getScriptResource(String path) throws IOException;
  
  void evaluateScript(Context cx, String script, Scriptable scope) throws IOException {
    Reader reader = new InputStreamReader(getScriptResource(script));
    cx.evaluateReader(scope, reader, script, 1, null);
  }

  abstract protected void onLoaded() throws IOException;

  protected void addScript(String script) throws IOException {
    Context cx = Context.enter();
    cx.setOptimizationLevel(-1);
    try {
      evaluateScript(cx, script, getTopLevelScope(cx));
    } finally {
      cx.exit();
    }
  }
    
  XQueryExpression compileQuery() {
    try {
      final StaticQueryContext sqc = new StaticQueryContext(config, true);
      final XQueryExpression e = sqc.compileQuery(new InputStreamReader(getScriptResource("srgs/grxml2js.xq")));
      return e;
    } catch (Exception exc) {
      throw new RuntimeException(exc);
    }
  }
  
  void onLoadTopLevelScope(Context cx, Scriptable scope) throws IOException {
    evaluateScript(cx, "srgs/chartparser.js", scope);
    evaluateScript(cx, "srgs/srgs.js", scope);
    onLoaded();
  }
  
  Scriptable getTopLevelScope(Context cx) {
    try {
      synchronized (this) {
        if (scope == null) {
          scope = cx.initStandardObjects();
          onLoadTopLevelScope(cx, scope);
        }
      }
    } catch (Exception exc) {
      throw new RuntimeException(exc);
    }
    return scope;
  }

  public String evaluateInGrammarScope(String grammar, String script) {
    Context cx = Context.enter();
    try {
      final Scriptable grammarScope = getGrammarScope(cx, grammar);
      if (grammarScope == null) {
        return null;
      }
      Object obj = cx.evaluateString(grammarScope, script, "", 1, null);
      return obj == null ? null : obj.toString();
    } finally {
      cx.exit();
    }
  }
  
  Scriptable getGrammarScope(Context cx, String grammar) {
    final Scriptable scope = getTopLevelScope(cx);
    synchronized (grammarScopes) {
      Scriptable grammarScope = grammarScopes.get(grammar);
      if (grammarScope == null) {
        grammarScope = new NativeObject();
        grammarScope.setParentScope(scope);
        grammarScopes.put(grammar, grammarScope);
      }
      return grammarScope;
    }
  }
  
  boolean grammarIsLoaded(String grammar) {
    synchronized (grammarScopes) {
      return grammarScopes.containsKey(grammar);
    }
  }
  
  void loadGrammar(String file, InputStream src) {
    synchronized (this) {
      if (grxml2js == null) grxml2js = compileQuery();
    }
    DynamicQueryContext dqc = new DynamicQueryContext(config);
    Context cx = null;
    try {
      DocumentBuilder builder;
      synchronized (this) {
        builder = fac.newDocumentBuilder();
      }
      org.w3c.dom.Node contextNode = builder.parse(src);
      dqc.setContextItem(new DocumentWrapper(contextNode, "", config));
      Object result = grxml2js.evaluateSingle(dqc);
      cx = Context.enter();
      cx.setOptimizationLevel(-1);
      Scriptable scope = getGrammarScope(cx, file);
      Object obj = cx.evaluateString(scope, result.toString(), file, 1, null);
    } catch (Exception exc) {
      throw new RuntimeException(exc);
    } finally {
      if (cx != null) {
        Context.exit();
      }
    }
  }

  void enableGrammar(String file, boolean enabled) {
    Context cx = null;
    try {
      cx = Context.enter();
      cx.setOptimizationLevel(-1);
      Scriptable scope = getGrammarScope(cx, file);
      Object obj = cx.evaluateString(scope, "grammar.enabled="+enabled, file, 1, null);
    } catch (Exception exc) {
      throw new RuntimeException(exc);
    } finally {
      if (cx != null) {
        Context.exit();
      }
    }
  }

  // Enable or disable a rule
  public void setRuleState(String grammar, String rule, boolean enabled) {
    // @TODO
  }
  
  // Enable or disable a grammar
  public void setGrammarState(String grammar, boolean enabled, InputStream is) throws IOException {
    loadGrammar(grammar, is);
    enableGrammar(grammar, enabled);
  }
  
  // Add terms to a <one-of> element
  public void addWordTransition(String grammar, String rule, String words, boolean replace) {
    Context cx = null;
    try {
      cx = Context.enter();
      cx.setOptimizationLevel(-1);
      words = "["+words+"]";
      Scriptable scope = getGrammarScope(cx, grammar);
      Object obj = cx.evaluateString(scope,
                                     "grammar.AddWordTransition("+rule+","+words+", "+replace+")",
                                     "addWordTransition", 1, null);
    } catch (Exception exc) {
      throw new RuntimeException(exc);
    } finally {
      if (cx != null) {
        Context.exit();
      }
    }            
  }
}
