package io.github.stag7824.mergeradar.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/** Entry point: {@code merge-radar health ...} and {@code merge-radar issues ...}. */
@Command(
    name = "merge-radar",
    mixinStandardHelpOptions = true,
    versionProvider = MergeRadar.Version.class,
    description = "Find open source issues you can actually fix and get merged.",
    subcommands = {HealthCommand.class, IssuesCommand.class, CacheCommand.class})
public final class MergeRadar implements Runnable {

  @CommandLine.Spec CommandLine.Model.CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }

  public static void main(String[] args) {
    System.exit(commandLine().execute(args));
  }

  static CommandLine commandLine() {
    CommandLine commandLine = new CommandLine(new MergeRadar());
    commandLine.setCaseInsensitiveEnumValuesAllowed(true);
    commandLine.registerConverter(java.time.Duration.class, new DurationConverter());
    commandLine.setExecutionExceptionHandler((exception, cmd, parseResult) -> {
      cmd.getErr().println("error: " + exception.getMessage());
      return 2;
    });
    return commandLine;
  }

  /** Reads the version from the jar manifest. */
  static final class Version implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() {
      String version = MergeRadar.class.getPackage().getImplementationVersion();
      return new String[] {"merge-radar " + (version == null ? "dev" : version)};
    }
  }
}
