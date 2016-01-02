package com.github.unktomi;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.ServletException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;


import com.amazonaws.auth.ClasspathPropertiesFileCredentialsProvider;
import com.gravity.goose.Article;
import com.gravity.goose.Configuration;
import com.gravity.goose.Goose;
import com.ivona.services.tts.IvonaSpeechCloudClient;
import com.ivona.services.tts.model.*;

import net.sf.saxon.dom.DocumentWrapper;
import net.sf.saxon.query.DynamicQueryContext;
import net.sf.saxon.query.StaticQueryContext;
import net.sf.saxon.query.XQueryExpression;
import org.json.simple.JSONObject;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.json.simple.JSONValue;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;

/**
 * Created by unktomi
 */
public class VoiceInteractionServer extends AbstractHandler {

    abstract class SpeechRecognizer {

        final File contentRoot;

        SpeechRecognizer(File contentRoot) {
            this.contentRoot = contentRoot;
        }

        InputStream getScriptResource(String path) {
            try {
                return new FileInputStream(contentRoot.toString()+"/" + path);
            } catch (IOException exc) {
                throw new RuntimeException(exc);
            }
        }

        void evaluateScript(Context cx, String script) throws IOException {
            Reader reader = new InputStreamReader(getScriptResource(script));
            cx.evaluateReader(scope, reader, script, 1, null);
        }

        Scriptable scope;
        XQueryExpression grxml2js;
        final net.sf.saxon.Configuration config = net.sf.saxon.Configuration.newConfiguration();

        XQueryExpression compileQuery() {
            try {
                final StaticQueryContext sqc = new StaticQueryContext(config, true);
                final XQueryExpression e = sqc.compileQuery(new InputStreamReader(getScriptResource("content/srgs/grxml2js.xq")));
                return e;
            } catch (Exception exc) {
                throw new RuntimeException(exc);
            }
        }

        final Map<String, Scriptable> grammarScopes = new HashMap();

        void onLoadTopLevelScope(Context cx, Scriptable scope) throws IOException {
            evaluateScript(cx, "content/srgs/chartparser.js");
            evaluateScript(cx, "content/srgs/srgs.js");
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

        final DocumentBuilderFactory fac = DocumentBuilderFactory.newInstance();

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

        // Enable or disable a rule
        public void onSetRule(HttpServletRequest req, final HttpServletResponse response) throws IOException {

        }

        // Enable or disable a grammar
        public void onSetGrammar(HttpServletRequest req, final HttpServletResponse response) throws IOException {
            String grammar = req.getParameter("grammar");
            boolean enabled = Boolean.parseBoolean(req.getParameter("state"));
            loadGrammar(grammar, req.getInputStream());
            enableGrammar(grammar, enabled);
        }

        // Add terms to a <one-of> element
        public void onAddWordTransition(HttpServletRequest req, final HttpServletResponse response) throws IOException {
            Context cx = null;
            try {
                cx = Context.enter();
                cx.setOptimizationLevel(9);
                String grammar = req.getParameter("grammar");
                String rule = req.getParameter("rule");
                String words = "["+req.getParameter("words")+"]";
                String replace = req.getParameter("replace");
                Scriptable scope = getGrammarScope(cx, grammar);
                Object obj = cx.evaluateString(scope, "grammar.AddWordTransition("+rule+","+words+", "+replace+")", "addWordTransition", 1, null);
            } catch (Exception exc) {
                throw new RuntimeException(exc);
            } finally {
                if (cx != null) {
                    Context.exit();
                }
            }            
        }

        abstract public void onAudioInput(HttpServletRequest req, final HttpServletResponse response) throws IOException;


    }

    class IBMSpeechRecognizer extends SpeechRecognizer {
        String username;
        String password;
        IBMSpeechRecognizer(File root) {
            super(root);
            Properties props = new Properties();
            try {
                props.load(getClass().getResourceAsStream("/IbmWatsonCredentials.properties"));
                username = props.getProperty("username");
                password = props.getProperty("password");
            } catch (Exception exc) {
                throw new RuntimeException(exc);
            }
        }

        @Override
        void onLoadTopLevelScope(Context cx, Scriptable scope) throws IOException {
            super.onLoadTopLevelScope(cx, scope);
            evaluateScript(cx, "content/srgs/ibm-watson-srgs.js");
        }

        @Override
        public void onAudioInput(HttpServletRequest req, final HttpServletResponse response) throws IOException {
            Context cx = Context.enter();
            cx.setOptimizationLevel(-1);
            try {
                final String grammar = req.getParameter("grammar");
                if (grammar != null) {
                    if (!grammarIsLoaded(grammar)) {
                        // fallback
                        loadGrammar(grammar, getScriptResource("content/srgs/grammars/" + grammar));
                    }
                } else {
                    throw new RuntimeException("Expected a grammar parameter");
                }
                String lang = req.getParameter("lang");
                if (lang == null) {
                    lang = "en-US";
                }
                if (lang.equalsIgnoreCase("en-UK")) {
                    lang = "en-US"; // watson doesn't support en-UK
                }
                final InputStream audio = req.getInputStream();
                final String mimeType = req.getContentType();
                final URL u = new URL("https://stream.watsonplatform.net/speech-to-text/api/v1/recognize?model="+lang+"_BroadbandModel");
                final HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                final String userCredentials = username+":"+password;
                final String basicAuth = "Basic " + new String(new sun.misc.BASE64Encoder().encode(userCredentials.getBytes()));
                conn.setRequestProperty("Authorization", basicAuth);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "audio/flac");
                //conn.setRequestProperty("Content-Length", "" + req.getContentLength());
                conn.setUseCaches(false);
                conn.setDoInput(true);
                conn.setDoOutput(true);
                String[] comps = mimeType.split(";");
                int rate = 44100;
                int channels = 1;
                for (String comp: comps) {
                    String[] elem = comp.split("=");
                    if (elem.length == 2) {
                        String k = elem[0].trim().toLowerCase();
                        String v = elem[1].trim().toLowerCase();
                        if (k.equals("rate")) {
                            rate = Integer.parseInt(v);
                        } else if (k.equals("channels")) {
                            channels = Integer.parseInt(v);
                        }
                    } else {
                    }
                }
                transcodeToViaPipe(audio, conn.getOutputStream(), "s16le", channels, rate, "flac", 1, 16000);
                final StringWriter writer = new StringWriter();
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    writer.write(line);
                    writer.write("\n");
                }
                final String result = writer.toString();
                final Scriptable grammarScope = getGrammarScope(cx, grammar);
                final String script = "applySrgsToWatsonResults(grammar, "+result+")";
                // parse(input, grammar, grammar.$root).out
                final Object obj = cx.evaluateString(grammarScope, script, "", 1, null);
                response.getWriter().write(obj.toString());
            } finally {
                Context.exit();
            }
        }
    }

    class OxfordSpeechRecognizer extends SpeechRecognizer {
        final String instanceId = UUID.randomUUID().toString();
        String clientSecret;
        String clientId;
        OxfordSpeechRecognizer(File root) {
            super(root);
            Properties props = new Properties();
            try {
                props.load(getClass().getResourceAsStream("/OxfordCredentials.properties"));
                clientSecret = props.getProperty("clientSecret");
                clientId = props.getProperty("clientId");
            } catch (Exception exc) {
               throw new RuntimeException(exc);
            }
        }

        @Override
        void onLoadTopLevelScope(Context cx, Scriptable scope) throws IOException {
            super.onLoadTopLevelScope(cx, scope);
            evaluateScript(cx, "content/srgs/oxford-srgs.js");
        }

        String getAccessToken(String clientId, String clientSecret) throws MalformedURLException, IOException {
            final URL u = new URL("https://oxford-speech.cloudapp.net/token/issueToken");
            final String content = "grant_type=client_credentials&client_id="+URLEncoder.encode(clientId)+"&client_secret="+ URLEncoder.encode(clientSecret)+"&scope=https://speech.platform.bing.com";
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) u.openConnection();
                conn.setRequestMethod("POST");
                conn.setUseCaches(false);
                conn.setDoInput(true);
                conn.setDoOutput(true);
                conn.getOutputStream().write(content.getBytes("UTF-8"));
                conn.getOutputStream().close();
                Reader r = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                JSONObject obj = (JSONObject) JSONValue.parse(r);
                return obj.get("access_token").toString();
            } catch (Exception exc) {
                exc.printStackTrace();
                if (conn != null) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
                    exc.printStackTrace();
                    String line;
                    StringWriter writer = new StringWriter();
                    while ((line = reader.readLine()) != null) {
                        writer.write(line);
                        writer.write("\n");
                    }
                    final String result = writer.toString();
                    throw new RuntimeException(result, exc);
                }
                throw new RuntimeException(exc);
            }
        }

        @Override
        public void onAudioInput(HttpServletRequest req, final HttpServletResponse response) throws IOException {
            Context cx = Context.enter();
            cx.setOptimizationLevel(9);
            try {
                final String grammar = req.getParameter("grammar");
                if (grammar != null) {
                    if (!grammarIsLoaded(grammar)) {
                        // fallback
                        loadGrammar(grammar, getScriptResource("content/srgs/grammars/" + grammar));
                    }
                } else {
                    throw new RuntimeException("Expected a grammar parameter");
                }
                String lang = req.getParameter("lang");
                if (lang == null) {
                    lang = "en-US";
                }
                if (lang.equalsIgnoreCase("en-UK")) {
                    lang = "en-GB";
                }
                final InputStream audio = req.getInputStream();
                final String mimeType = req.getContentType();
                String reqId = UUID.randomUUID().toString();
                final String params = "version=3.0&scenarios=ulm&requestid="+reqId+"&instanceid="+instanceId+"&appID=D4D52672-91D7-4C74-8AD8-42B1D98141A5&format=json&locale="+lang+"&device.os=Windows OS";
                final URL u = new URL("https://speech.platform.bing.com/recognize/query?"+params);
                final HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                final String accessToken = getAccessToken(clientId, clientSecret);
                final String auth = "Bearer " + accessToken;
                conn.setRequestProperty("Authorization", auth);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "audio/wav; samplerate="+16000);
                //conn.setRequestProperty("Content-Length", "" + req.getContentLength());
                conn.setUseCaches(false);
                conn.setDoInput(true);
                conn.setDoOutput(true);
                String[] comps = mimeType.split(";");
                int rate = 44100;
                int channels = 1;
                for (String comp: comps) {
                    String[] elem = comp.split("=");
                    if (elem.length == 2) {
                        String k = elem[0].trim().toLowerCase();
                        String v = elem[1].trim().toLowerCase();
                        if (k.equals("rate")) {
                            rate = Integer.parseInt(v);
                        } else if (k.equals("channels")) {
                            channels = Integer.parseInt(v);
                        }
                    } else {
                    }
                }
                transcodeToViaPipe(audio, conn.getOutputStream(), "s16le", channels, rate, "wav", 1, 16000);
                final StringWriter writer = new StringWriter();
                try {
                    conn.getOutputStream().close();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        writer.write(line);
                        writer.write("\n");
                    }
                    final String result = writer.toString();
                    final Scriptable grammarScope = getGrammarScope(cx, grammar);
                    final String script = "applySrgsToOxfordResults(grammar, " + result + ")";
                    // parse(input, grammar, grammar.$root).out
                    final Object obj = cx.evaluateString(grammarScope, script, "", 1, null);
                    response.getWriter().write(obj.toString());
                } catch (Exception exc) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
                    exc.printStackTrace();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        writer.write(line);
                        writer.write("\n");
                    }
                    final String result = writer.toString();
                    throw new RuntimeException(result);
                }
            } finally {
                Context.exit();
            }
        }
    }


    class GoogleSpeechRecognizer extends SpeechRecognizer {
        GoogleSpeechRecognizer(File root) {
            super(root);
        }

        @Override
        void onLoadTopLevelScope(Context cx, Scriptable scope) throws IOException {
            super.onLoadTopLevelScope(cx, scope);
            evaluateScript(cx, "content/srgs/google-srgs.js");
        }

        @Override
        public void onAudioInput(HttpServletRequest req, final HttpServletResponse response) throws IOException {
            Context cx = Context.enter();
            cx.setOptimizationLevel(9);
            try {
                final String grammar = req.getParameter("grammar");
                if (grammar != null) {
                    if (!grammarIsLoaded(grammar)) {
                        // fallback
                        loadGrammar(grammar, getScriptResource("content/srgs/grammars/" + grammar));
                    }
                } else {
                    throw new RuntimeException("Expected a grammar parameter");
                }
                String lang = req.getParameter("lang");
                if (lang == null) {
                    lang = "en-US";
                }
                final InputStream audio = req.getInputStream();
                final String mimeType = req.getContentType();
                final URL u = new URL("https://www.google.com/speech-api/v2/recognize?key=AIzaSyBOti4mM-6x9WDnZIjIeyEU21OpBXqWBgw&output=json&lang="+lang);
                final HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                conn.setRequestMethod("POST");
                final String[] comps = mimeType.split(";");
                int rate = 44100;
                int channels = 1;
                for (String comp: comps) {
                    String[] elem = comp.split("=");
                    if (elem.length == 2) {
                        String k = elem[0].trim().toLowerCase();
                        String v = elem[1].trim().toLowerCase();
                        if (k.equals("rate")) {
                            rate = Integer.parseInt(v);
                        } else if (k.equals("channels")) {
                            channels = Integer.parseInt(v);
                        }
                    } else {
                    }
                }
                conn.setUseCaches(false);
                conn.setDoInput(true);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "audio/x-flac; rate=" + rate);
                transcodeToViaPipe(audio, conn.getOutputStream(), "s16le", channels, rate, "flac", 1, rate);
                conn.getOutputStream().close();
                final StringWriter writer = new StringWriter();
                final BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String line;
                int lineno = 1;
                while ((line = reader.readLine()) != null) {
                    if (lineno > 1) { // skip the first line
                        writer.write(line);
                        writer.write("\n");
                    }
                    lineno++;
                }
                String result = writer.toString();
                final Scriptable grammarScope = getGrammarScope(cx, grammar);
                if (result.length() == 0) {
                    result ="{result:[]}";
                }
                final String script = "applySrgsToGoogleResults(grammar, "+result+")";
                // parse(input, grammar, grammar.$root).out
                final Object obj = cx.evaluateString(grammarScope, script, "", 1, null);
                response.getWriter().write(obj.toString());
            } finally {
                Context.exit();
            }
        }
    }

    private IvonaSpeechCloudClient speechCloud;

    private void init() {
        speechCloud = new IvonaSpeechCloudClient(
                new ClasspathPropertiesFileCredentialsProvider("IvonaCredentials.properties"));
        speechCloud.setEndpoint("https://tts.eu-west-1.ivonacloud.com");
    }

    static void copyTo(InputStream in, OutputStream out, boolean closeOutput) {
        byte[] buffer = new byte[8192];
        int read;
        try {
            while ((read = in.read(buffer, 0, buffer.length)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
            if (closeOutput) out.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    IBMSpeechRecognizer ibmSpeechRecognizer;

    synchronized IBMSpeechRecognizer getIbmSpeechRecognizer(File root) {
        if (ibmSpeechRecognizer == null) {
            ibmSpeechRecognizer = new IBMSpeechRecognizer(root);
        }
        return ibmSpeechRecognizer;
    }

    GoogleSpeechRecognizer googleSpeechRecognizer;

    synchronized GoogleSpeechRecognizer getGoogleSpeechRecognizer(File root) {
        if (googleSpeechRecognizer == null) {
            googleSpeechRecognizer = new GoogleSpeechRecognizer(root);
        }
        return googleSpeechRecognizer;
    }

    OxfordSpeechRecognizer oxfordSpeechRecognizer;

    synchronized OxfordSpeechRecognizer getOxfordSpeechRecognizer(File root) {
        if (oxfordSpeechRecognizer == null) {
            oxfordSpeechRecognizer = new OxfordSpeechRecognizer(root);
        }
        return oxfordSpeechRecognizer;
    }

    static File getRealPath(ServletContext context, String path) {
        if (context == null) return new File("./"+path);
        return new File(context.getRealPath(path));
    }

    static ThreadLocal<Goose> gooseLocal = new ThreadLocal<Goose> () {
            @Override
            protected Goose initialValue() {
                final Configuration config = new Configuration();
                config.setEnableImageFetching(false);
                final Goose goose = new Goose(config);
                return goose;
            }
        };

    enum SearchType { ITunes, Google };

    public void handle(String target,
                       Request baseRequest,
                       HttpServletRequest request,
                       HttpServletResponse response)
            throws IOException, ServletException {
        System.err.println(target);
        if (baseRequest.isHandled()) return;
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate"); // HTTP 1.1.
        response.setHeader("Pragma", "no-cache"); // HTTP 1.0.
        response.setDateHeader("Expires", 0); // Proxies.
        baseRequest.setHandled(true);
        response.setStatus(HttpServletResponse.SC_OK);
        if (target.startsWith("/ibm-recognize")) {
            getIbmSpeechRecognizer(getRealPath(request.getServletContext(), "/")).onAudioInput(request, response);
            return;
        } else if (target.startsWith("/ibm-set-rule")) {
            getIbmSpeechRecognizer(getRealPath(request.getServletContext(), "/")).onSetRule(request, response);
            return;
        } else if (target.startsWith("/ibm-set-grammar")) {
            getIbmSpeechRecognizer(getRealPath(request.getServletContext(), "/")).onSetGrammar(request, response);
            return;
        } else if (target.startsWith("/ibm-add-word-transition")) {
            getIbmSpeechRecognizer(getRealPath(request.getServletContext(), "/")).onAddWordTransition(request, response);
            return;
        } else if (target.startsWith("/oxford-recognize")) {
            getOxfordSpeechRecognizer(getRealPath(request.getServletContext(), "/")).onAudioInput(request, response);
            return;
        } else if (target.startsWith("/oxford-set-rule")) {
            getOxfordSpeechRecognizer(getRealPath(request.getServletContext(), "/")).onSetRule(request, response);
            return;
        } else if (target.startsWith("/oxford-set-grammar")) {
            getOxfordSpeechRecognizer(getRealPath(request.getServletContext(), "/")).onSetGrammar(request, response);
            return;
        } else if (target.startsWith("/oxford-add-word-transition")) {
            getOxfordSpeechRecognizer(getRealPath(request.getServletContext(), "/")).onAddWordTransition(request, response);
            return;
        } else if (target.startsWith("/google-recognize")) {
            getGoogleSpeechRecognizer(getRealPath(request.getServletContext(), "/")).onAudioInput(request, response);
            return;
        } else if (target.startsWith("/google-set-rule")) {
            getGoogleSpeechRecognizer(getRealPath(request.getServletContext(), "/")).onSetRule(request, response);
            return;
        } else if (target.startsWith("/google-set-grammar")) {
            getGoogleSpeechRecognizer(getRealPath(request.getServletContext(), "/")).onSetGrammar(request, response);
            return;
        } else if (target.startsWith("/google-add-word-transition")) {
            getGoogleSpeechRecognizer(getRealPath(request.getServletContext(), "/")).onAddWordTransition(request, response);
            return;
        } else if (target.equals("/read.aac")) {
            doRead(request, response);
            return;
        } else if (target.equals("/play.aac")) {
            doPlay(request, response);
            return;
        }
        String voice = request.getParameter("voice");
        if (voice == null) {
            response.setStatus(HttpServletResponse.SC_NOT_ACCEPTABLE);
            response.setContentType("text;charset=utf-8");
            response.getWriter().println("Missing parameter: voice='...'");
            return;
        }
        String inputText = request.getParameter("speechMarkup");
        if (inputText == null) {
            BufferedReader reader = new BufferedReader(request.getReader());
            String line;
            StringBuilder b = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                b.append(line);
                b.append("\n");
            }
            inputText = b.toString();
        }
        contactSpeechCloud(File.createTempFile("speechcloud", ".mp3"), voice, inputText, response, ".aac");
    }

    private static String readTextFile(String url) {
        try {
            URL u = new URL(url);
            BufferedReader reader = new BufferedReader(new InputStreamReader(u.openStream()));
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
                builder.append("\n");
            }
            return builder.toString();
        } catch (Exception exc) {
            throw new RuntimeException(exc);
        }
    }

    private void doPlay(HttpServletRequest request, HttpServletResponse response) throws IOException {
        final String url = request.getParameter("src");
        String contentType = request.getParameter("contentType");
        int contentLength = -1;
        URL u = new URL(url);
        HttpURLConnection connection = (HttpURLConnection) u.openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.connect();
        contentType = connection.getContentType();
        if (contentType.startsWith("text/")) {
            doRead(request, response);
            return;
        }
        contentLength = connection.getContentLength();
        if (!contentType.startsWith("audio/")&& !contentType.startsWith("video/")) {
            response.setStatus(HttpServletResponse.SC_NOT_ACCEPTABLE);
            return;
        }
        if (contentType.equals("audio/mpeg3") || u.getPath().endsWith(".mp3")) {
            response.setContentType("audio/aac");
            transcodeToAAC(connection.getInputStream(), response, "mp3");
        } else {
            if (contentLength > 0) {
                //response.setContentLength(contentLength);
            }
            response.setContentType("audio/aac");
            int slash = contentType.indexOf("/");
            String inputFormat = contentType.substring(slash+1);
            if (u.getPath().endsWith(".mp3")) {
                inputFormat = "mp3";
            }
            transcodeToAAC(connection.getInputStream(), response, inputFormat);
            //copyTo(connection.getInputStream(), response.getOutputStream(), false);
        }
        return;
    }

    private void doRead(HttpServletRequest request, HttpServletResponse response) throws IOException {
        final String url = request.getParameter("src");
        String contentType = request.getParameter("contentType");
        String title = request.getParameter("title");
        if (title == null) title = "that";
        int contentLength = -1;
        URL u = new URL(url);
        HttpURLConnection connection = (HttpURLConnection) u.openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.connect();
        contentType = connection.getContentType();
        if (contentType != null && !contentType.startsWith("text/")) {
            doPlay(request, response);
            return;
        }
        contentLength = connection.getContentLength();
        try {
            String voice = request.getParameter("voice");
            if (contentType != null && contentType.startsWith("text/")) {
                final Configuration config = new Configuration();
                config.setEnableImageFetching(false);
                final Goose goose = new Goose(config);
                final Article article = goose.extractContent(url);
                String inputText = article.cleanedArticleText();
                if (article.title() != null) {
                    title = article.title();
                    inputText = article.title() + "\n" + inputText;
                }
                System.err.println(inputText);
                if (inputText != null && inputText.length() > 0) {
                    contactSpeechCloud(File.createTempFile("speechcloud", ".mp3"), voice, inputText, response, ".aac");
                    return;
                }
            }
            contactSpeechCloud(File.createTempFile("speechcloud", ".mp3"), voice, "I'm sorry I can't read "+title, response, ".aac");
        } catch (Throwable t) {
            t.printStackTrace();
            throw new RuntimeException(t);
        }
    }


    void writeJSONResponse(String responseId, InputStream in, OutputStream out) {
        PrintWriter writer = new PrintWriter(out);
        writer.print("{\"" + responseId + "\":");
        writer.flush();
        copyTo(in, out, false);
        writer.print("}");
        writer.flush();
    }


    static Map<String, String> MimeType2Format = new HashMap();
    static {
        MimeType2Format.put("3gpp", "3gp");
        MimeType2Format.put("x-m4v", "m4v");
    }

    static String getInputFormat(String mimeType) {
        String result = MimeType2Format.get(mimeType);
        return result != null ? result : mimeType;
    }

    void transcodeToAAC(final InputStream in, HttpServletResponse response, String inputFormat)  {
        try {
            response.setContentType("audio/aac");
            response.flushBuffer();
            transcodeToViaPipe(in, response.getOutputStream(), inputFormat, 2, 44100, "adts", 2, 44100);
        } catch (IOException exc) {
            throw new RuntimeException(exc);
        }
    }

    void transcodeToViaPipe(final InputStream in, OutputStream out, String inputFormat, int inputChannels, int inputRate, String outputFormat, int channels, int rate) {
        transcodeTo(in, "-", out, inputFormat, inputChannels, inputRate, outputFormat, channels, rate,  "pipe:1");
    }

    void transcodeToViaFile(final InputStream in, OutputStream out, String inputFormat, int inputChannels, int inputRate, String outputFormat, int channels, int rate) {
        File file = null;
        FileInputStream fis = null;
        String fileName = null;
        File inFile = null;
        try {
            inFile = File.createTempFile("transcodein", "ffmpeg");
            FileOutputStream fout = new FileOutputStream(inFile);
            copyTo(in, fout, true);
            file = File.createTempFile("transcodeout", "ffmpeg");
            fileName = file.getAbsolutePath()+"."+outputFormat;
            transcodeTo(null, inFile.getAbsolutePath(), out, inputFormat, inputChannels, inputRate, outputFormat, channels, rate, fileName);
            fis = new FileInputStream(fileName);
            copyTo(fis, out, false);
        } catch (IOException exc) {
            throw new RuntimeException(exc);
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException ignored) {

                }
                inFile.delete();
                file.delete();
                new File(fileName).delete();
            }
        }
    }

    void transcodeTo(final InputStream in, String inFile, OutputStream out, String inputFormat, int inputChannels, int inputRate, String outputFormat, int channels, int rate, String terminator) {
        Process proc = null;
        try {
            final Object monitor = new Object();
            String command = FFMPEG()+" -f "+getInputFormat(inputFormat)+" -ac "+inputChannels+ " -ar "+inputRate+" -i "+inFile+" -vn -f "+outputFormat+" -ac "+channels+" -ar "+rate+" "+terminator;
            System.err.println(command);
            proc = Runtime.getRuntime().exec(command);
            final InputStream err = proc.getErrorStream();
            new Thread() {
                public void run() {
                    int b;
                    try {
                        while ((b = err.read()) > 0) {
                            System.err.write(b);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }.start();
            final Process process = proc;
            new Thread() {
                public void run() {
                    try {
                        int exit = process.waitFor();
                        System.err.println("ffmpeg returned " + exit);
                        synchronized (monitor) {
                            monitor.notifyAll();
                        }
                    } catch (InterruptedException exc) {

                    }
                }
            }.start();
            if (in != null) {
                new Thread() {
                    public void run() {
                        copyTo(in, process.getOutputStream(), true);
                    }
                }.start();
            }
            if (out != null) {
                copyTo(proc.getInputStream(), out, false);
            }
        } catch (Exception exc) {
            exc.printStackTrace();
        } finally {
            if (proc != null) {
                proc.destroy();
            }
        }
    }

    String contactSpeechCloud(File outputFile, String voiceSelection, String inputMarkup, HttpServletResponse response, String requestedFormat) throws IOException {
        init();

        CreateSpeechRequest createSpeechRequest = new CreateSpeechRequest();
        Input input = new Input();
        Voice voice = new Voice();
        voice.setName(voiceSelection);
        input.setData(inputMarkup.substring(0, Math.min(inputMarkup.length(), 8192)));
        OutputFormat outputFormat = new OutputFormat();
        outputFormat.setCodec("MP3");
        outputFormat.setSampleRate((short)44100);
        createSpeechRequest.setOutputFormat(outputFormat);

        createSpeechRequest.setInput(input);
        createSpeechRequest.setVoice(voice);
        createSpeechRequest.setMethodType(MethodType.POST);
        FileOutputStream outputStream = null;
        File target = null;
        Process proc = null;
        try {
            long start = System.currentTimeMillis();
            CreateSpeechResult createSpeechResult = speechCloud.createSpeech(createSpeechRequest);
            response.setStatus(HttpServletResponse.SC_OK);
            final InputStream in = createSpeechResult.getBody();
            if (requestedFormat.equals(".aac")) {
                response.setContentType("audio/aac");
                response.flushBuffer();
                final Object monitor = new Object();
                proc = Runtime.getRuntime().exec(FFMPEG()+" -f mp3 -ac 2 -i - -f adts pipe:1");
                final InputStream err = proc.getErrorStream();
                new Thread() {
                    public void run() {
                        int b;
                        try {
                            while ((b = err.read()) > 0) {
                                System.err.write(b);
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }.start();
                final Process process = proc;
                new Thread() {
                    public void run() {

                        try {
                            int exit = process.waitFor();
                            System.err.println("ffmpeg returned "+exit);
                            synchronized (monitor) {

                                monitor.notifyAll();
                            }
                        } catch (InterruptedException exc) {

                        }
                    }
                }.start();
                new Thread() {
                    public void run() {
                       copyTo(in, process.getOutputStream(), true);
                    }
                }.start();

                copyTo(proc.getInputStream(), response.getOutputStream(), false);
            } else {
                outputStream = new FileOutputStream(outputFile);
                String outputFileName = outputFile.getName();
                copyTo(in, outputStream, true);
                long end = System.currentTimeMillis();
                System.err.println("Speech cloud elapsed=" + (end - start) + "ms");
                start = end;
                final String targetFileName = outputFileName.replace(".mp3", requestedFormat);
                final Process process = Runtime.getRuntime().exec(FFMPEG()+" -i " + outputFile.getAbsolutePath() + " -y " + targetFileName);
                try {
                    process.waitFor();
                } catch (InterruptedException exc) {
                    throw new RuntimeException(exc);
                }
                target = new File(targetFileName);
                long len = target.length();
                response.setContentType("audio/mp4");
                response.setStatus(HttpServletResponse.SC_OK);
                response.setContentLength((int) len);
                copyTo(new FileInputStream(target), response.getOutputStream(), false);
                end = System.currentTimeMillis();
                System.err.println("Transcode elapsed=" + (end - start) + "ms");
                return targetFileName;
            }

        } finally {
            if (outputStream != null) {
                outputStream.close();
            }
            if (target != null) {
                target.delete();
            }
            if (outputFile != null) {
                outputFile.delete();
            }
            if (proc != null) {
                proc.destroy();
            }
        }
        return null;
    }


    private String FFMPEG() {
        return "ffmpeg";
    }

    public static void main(String[] args) throws Exception
    {
        int port = 8081;
        final List<String> pos = new ArrayList();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-port")) {
                i++;
                port = Integer.parseInt(args[i]);
            } else {
                pos.add(args[i]);
            }
        }
        final Server server = new Server(port);
        final ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setDirectoriesListed(true);
        resourceHandler.setResourceBase("content");;
        // Add the ResourceHandler to the server.
        HandlerList handlers = new HandlerList();
        handlers.setHandlers(new Handler[] { resourceHandler, new VoiceInteractionServer() });
        server.setHandler(handlers);
        server.start();
    }
}

