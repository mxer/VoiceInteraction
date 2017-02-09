package com.google.oasis.voiceinteraction;
import java.util.*;
import java.io.*;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.ErrorListener;
import javax.xml.transform.TransformerException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.parsers.SAXParser;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import net.sf.saxon.query.DynamicQueryContext;
import net.sf.saxon.query.StaticQueryContext;
import net.sf.saxon.query.XQueryExpression;
import net.sf.saxon.trace.XQueryTraceListener;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;

public abstract class GRXMLSpeechRecognizer {

  private Scriptable scope;
  private XQueryExpression grxml2js;
  private final net.sf.saxon.Configuration config = net.sf.saxon.Configuration.newConfiguration();
  private final Map<String, Scriptable> grammarScopes = new HashMap();
  private final SAXParserFactory saxParserFactory = SAXParserFactory.newInstance();


  public GRXMLSpeechRecognizer() {
    config.setErrorListener(new ErrorListener() {
        @Override
        public void error(TransformerException exc) {
          note(exc.getMessage());
        }
        @Override
        public void fatalError(TransformerException exc) {
          note(exc.getMessage());
        }
        @Override
        public void warning(TransformerException exc) {
          note(exc.getMessage());
        }
      });
    try {
      XQueryTraceListener traceListener = new XQueryTraceListener();
      config.setTraceListener(traceListener);
      traceListener.setOutputDestination(new java.io.PrintStream(new java.io.FileOutputStream("/sdcard/saxon.txt"), true));
    } catch (Exception exc) {
    }
    saxParserFactory.setNamespaceAware(true);
  }

  protected void note(String text) {}

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


  public String[] evaluateInAllGrammarScopes(String script) {
    final Set<String> grammars = new TreeSet();
    synchronized (grammarScopes) {
      for (String grammar: grammarScopes.keySet()) {
        grammars.add(grammar);
      }
    }
    for (String grammar: grammars) {
      final String result = evaluateInGrammarScope(grammar, script);
      if (result != null) {
        return new String[] {grammar, result};
      }
    }
    return null;
  }

  public String evaluateInGrammarScope(String grammar, String script) {
    Context cx = Context.enter();
    cx.setOptimizationLevel(-1);
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

  void loadGrammar(String file, InputStream is) {
    synchronized (this) {
      if (grxml2js == null) grxml2js = compileQuery();
    }
    DynamicQueryContext dqc = new DynamicQueryContext(config);
    Context cx = null;
    try {
      final InputSource src = new InputSource(new InputStreamReader(is, "UTF-8"));
      SAXParser saxParser;
      synchronized (saxParserFactory) {
        saxParser = saxParserFactory.newSAXParser();
      }
      final SAXSource ss = new SAXSource(saxParser.getXMLReader(), src);
      dqc.setContextItem(config.buildDocument(ss));
      Object result = grxml2js.evaluateSingle(dqc);
      cx = Context.enter();
      cx.setOptimizationLevel(-1);
      final Scriptable scope = getGrammarScope(cx, file);
      note("compiled grammar: "+result.toString());
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
    if (!grammarIsLoaded(grammar)) {
      loadGrammar(grammar, is);
    }
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





