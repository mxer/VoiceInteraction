package com.github.unktomi;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.ServletException;

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

import org.json.simple.JSONObject;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.json.simple.JSONValue;

/**
 * Created by unktomi
 */
public class VoiceInteractionServer extends AbstractHandler {

  abstract class CloudSpeechRecognizer extends GRXMLSpeechRecognizer {

    final File contentRoot;

    CloudSpeechRecognizer(File contentRoot) {
      this.contentRoot = contentRoot;
    }

    @Override

    protected InputStream getScriptResource(String path) throws IOException {

      return new FileInputStream(contentRoot.toString()+"/content/" + path);
    }

    abstract public void onAudioInput(HttpServletRequest req, final HttpServletResponse response) throws IOException;

    // Enable or disable a rule
    public void onSetRule(HttpServletRequest req, final HttpServletResponse response) throws IOException {
 
      String grammar = req.getParameter("grammar");
      String rule = req.getParameter("rule");
      String state = req.getParameter("state");
      setRuleState(grammar, rule, Boolean.parseBoolean(state));
    }

    // Enable or disable a grammar
    public void onSetGrammar(HttpServletRequest req, final HttpServletResponse response) throws IOException {
      String grammar = req.getParameter("grammar");
      String state = req.getParameter("state");
      setGrammarState(grammar, Boolean.parseBoolean(state), req.getInputStream());
    }

    // Add terms to a <one-of> element
    public void onAddWordTransition(HttpServletRequest req, final HttpServletResponse response) throws IOException {
          
      String grammar = req.getParameter("grammar");
      String rule = req.getParameter("rule");
      String words = "["+req.getParameter("words")+"]";
      String replace = req.getParameter("replace");
      addWordTransition(grammar, rule, words, Boolean.parseBoolean(replace));
    }
  }


  class GoogleCloudSpeechRecognizer extends CloudSpeechRecognizer {
    GoogleCloudSpeechRecognizer(File root) {
      super(root);
    }

    @Override
    protected void onLoaded() throws IOException {
      addScript("srgs/google-srgs.js");
    }

    @Override
    public void onAudioInput(HttpServletRequest req, final HttpServletResponse response) throws IOException {
      final String grammar = req.getParameter("grammar");
      if (grammar != null) {
        if (!grammarIsLoaded(grammar)) {
          throw new RuntimeException("Grammar \""+grammar + "\" not found");
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
      if (result.length() == 0) {
        result ="{result:[]}";
      }
      final String script = "applySrgsToGoogleResults(grammar, "+result+")";
      result = evaluateInGrammarScope(grammar, script); 
      response.getWriter().write(result);
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



  GoogleCloudSpeechRecognizer googleSpeechRecognizer;

  synchronized GoogleCloudSpeechRecognizer getGoogleSpeechRecognizer(File root) {
    if (googleSpeechRecognizer == null) {
      googleSpeechRecognizer = new GoogleCloudSpeechRecognizer(root);
    }
    return googleSpeechRecognizer;
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
    if (target.startsWith("/google-recognize")) {
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

