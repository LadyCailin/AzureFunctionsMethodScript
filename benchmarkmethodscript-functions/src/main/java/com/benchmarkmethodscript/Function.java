package com.benchmarkmethodscript;

import com.laytonsmith.PureUtilities.CommandExecutor;
import com.laytonsmith.PureUtilities.Common.FileWriteMode;
import com.laytonsmith.PureUtilities.MapBuilder;
import com.laytonsmith.PureUtilities.Web.RequestSettings;
import com.laytonsmith.PureUtilities.Web.WebUtility;
import com.microsoft.applicationinsights.TelemetryClient;
import com.microsoft.applicationinsights.TelemetryConfiguration;
import com.microsoft.applicationinsights.channel.concrete.inprocess.InProcessTelemetryChannel;
import java.util.*;
import com.microsoft.azure.functions.annotation.*;
import com.microsoft.azure.functions.*;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;

/**
 * Runs benchmarks on MethodScript, and uploads them to the Application Insights module.
 */
public class Function {

	/**
	 * args[0] is the useragent that is used in the request to download the jar from methodscript.com. This is
	 * being treated as a password, so that it bypasses the cloudflare protection system. The secret is stored
	 * in an environment variable called USERAGENT in Azure, but when tested locally, you need to pass the string
	 * as the first argument here. If you don't know the password, you're not allowed to programmatically hit the
	 * site, sorry, but if the useragent starts with "http" it will instead change the download location,
	 * to the path you specified, so you can host it elsewhere yourself. (If, in the future, you're me, and you forgot
	 * the password, it's configured in Azure and Cloudflare, look there.)
	 *
	 * args[1] is the Application Insights instrumentation key. This can be the string "null" to bypass that part of
	 * the code. This is stored in INSTRUMENTATION_KEY in the Azure Function.
	 * @param args
	 */
	public static void main(String[] args) {
		String useragent = args[0];
		String instrumentationKey = args[1];
		if("null".equalsIgnoreCase(instrumentationKey)) {
			instrumentationKey = null;
		}
		if(useragent.startsWith("http")) {
			URL = useragent;
			useragent = null;
		}
		Function f = new Function();
		f.log.addLogHandler((l) -> System.out.println(l));
		try {
			f.execute(null, useragent, instrumentationKey);
		} catch (NullPointerException ex) {
			//
		}
	}

	private static String URL = "https://methodscript.com/MethodScript.jar";
	private static int CYCLES = 1;
	private final Log log = new Log();
	private TelemetryClient telemetry;

	private static enum Failure {
		EXCEPTION(-1),
		BAD_OUTPUT(-2);

		private final int value;

		private Failure(int value) {
			this.value = value;
		}

		public int value() {
			return this.value;
		}
	}

	@FunctionName("autoBenchmark")
	public void runAuto(
			@TimerTrigger(name = "runAuto", schedule = "0 1 * * *" /*daily*/)
			String timerInfo,
			ExecutionContext context
	) {
		try {
			run(null, context);
		} catch (NullPointerException ex) {
			//
		}
	}

	/**
	 * This function listens at endpoint "/api/HttpExample". Two ways to invoke it using "curl" command in bash: 1. curl
	 * -d "HTTP Body" {your host}/api/HttpExample 2. curl "{your host}/api/HttpExample?name=HTTP%20Query"
	 *
	 * @param request
	 * @param context
	 * @return
	 */
	@FunctionName("benchmark")
	public HttpResponseMessage run(
			@HttpTrigger(name = "run",
					methods = {HttpMethod.GET},
					authLevel = AuthorizationLevel.FUNCTION)
			HttpRequestMessage<Optional<String>> request,
			final ExecutionContext context) {
		context.getLogger().info("Java HTTP trigger processed a request.");

		log.clear();
		log.addLog("Starting function");

		String useragent = System.getenv("USERAGENT");
		String instrumentationKey = System.getenv("INSTRUMENTATION_KEY");
		if(System.getenv("CYCLES") != null) {
			CYCLES = Integer.parseInt(System.getenv("CYCLES"));
		}
		return execute(request, useragent, instrumentationKey);

	}

	private HttpResponseMessage execute(HttpRequestMessage<Optional<String>> request,
				String userAgent,
				String instrumentationKey) {
		try {
			File download = File.createTempFile("test", "");
			download.deleteOnExit();
			download = new File(download.getAbsolutePath() + ".jar");
			download.deleteOnExit();
			URL url = new URL(URL);
			RequestSettings settings = new RequestSettings();
			log.addLog("Downloading latest MethodScript jar from " + URL + " to " + download.getAbsolutePath());
			settings.setDownloadTo(download);
			settings.setDownloadStrategy(FileWriteMode.OVERWRITE);
			settings.setBlocking(true);
//			settings.setLogger(Logger.getGlobal());
			if(userAgent != null) {
				Map<String, List<String>> headers = MapBuilder.start("User-Agent", Arrays.asList(userAgent));
				settings.setHeaders(headers);
			}
			WebUtility.GetPage(url, settings);
			log.addLog("Downloaded, running tests");
			double average = runTests(download);
			log.addLog("Tests completed, average runtime " + average);

			if(instrumentationKey != null) {
				log.addLog("Sending telemetry data");
				{
					// Telemetry client setup
					TelemetryConfiguration configuration = new TelemetryConfiguration();
					configuration.setInstrumentationKey(instrumentationKey);
					configuration.setChannel(new InProcessTelemetryChannel());
					TelemetryClient tc = new TelemetryClient(configuration);
					String session = UUID.randomUUID().toString();
					tc.getContext().getSession().setId(session);
					tc.getContext().getSession().setIsNewSession(true);
					tc.getContext().getCloud().setRoleInstance("");
					tc.getContext().getInternal().setNodeName(session);
					telemetry = tc;
				}

				telemetry.trackEvent("ext.methodscript.perf",
						new HashMap<>(),
						MapBuilder.start("cycle", average));

				telemetry.flush();
				log.addLog("Telemetry data sent");
			}

			return request.createResponseBuilder(HttpStatus.OK).body("Log:\n" + log.getLog()).build();
		} catch (Throwable t) {
			StringWriter s = new StringWriter();
			s.append(log.getLog());
			PrintWriter w = new PrintWriter(s);
			t.printStackTrace(w);
			return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR).body(s.toString()).build();
		}
	}

	/**
	 * Runs the specified number of cycles against the jar, and returns the average time spent in seconds.
	 * @param jar
	 * @return
	 */
	private double runTests(File jar) {
		// The first run installs things and does other first-time setup, so we don't want to count this
		// one in our statistics.
		log.addLog("First run");
		double firstRun = runTest(jar);
		log.addLog("First run took " + firstRun + " seconds");
		double totalTime = 0;
		log.addLog("Beginning cycles, " + CYCLES + " total.");
		for(int i = 0; i < CYCLES; i++) {
			double runtime = runTest(jar);
			if(runtime < 0) {
				return runtime;
			}
			totalTime += runtime;
		}
		return (totalTime / CYCLES);
	}

	/**
	 * Runs a single cycle, and returns how long it took in seconds. If the execution failed, -1 is returned.
	 * @param jar
	 * @return
	 */
	private double runTest(File jar) {
		try {
			long start = System.currentTimeMillis();
			String output = CommandExecutor.Execute("java", "-jar", jar.getAbsolutePath(), "cycle");
			if(!"0".equals(output.replace("\r", "").replace("\n", ""))) {
				log.addLog("Got bad output, output was:");
				log.addLog(output);
				return Failure.BAD_OUTPUT.value();
			}
			long stop = System.currentTimeMillis();
			double seconds = (stop - start) / 1000.0;
			return seconds;
		} catch (InterruptedException | IOException ex) {
			return Failure.EXCEPTION.value();
		}
	}
}
