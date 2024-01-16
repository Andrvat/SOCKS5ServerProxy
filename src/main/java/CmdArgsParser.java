import lombok.Getter;
import org.apache.commons.cli.*;

import java.util.*;

public class CmdArgsParser {
    private static final int DEFAULT_PROXY_SERVER_PORT = 1080;

    private final Options cmdOptions = new Options();

    @Getter
    private int proxyServerPort;

    public CmdArgsParser() {
        OptionSettings proxyServerPortSettings = OptionSettings.builder()
                .opt("p")
                .longOpt("proxyServerPort")
                .hasArg(true)
                .description("Proxy server network port, through which clients can connect to it")
                .build();
        this.addAllSettingsToOptions(Collections.singletonList(proxyServerPortSettings));
    }

    private void addAllSettingsToOptions(List<OptionSettings> optionSettings) {
        for (OptionSettings option : optionSettings) {
            cmdOptions.addOption(option.getOpt(),
                    option.getLongOpt(),
                    option.getHasArg(),
                    option.getDescription());
        }
    }

    public void parseArguments(String[] args) throws ParseException {
        CommandLineParser parser = new DefaultParser();
        CommandLine commandLine = parser.parse(cmdOptions, args);

        try {
            proxyServerPort = Integer.parseInt(commandLine.getOptionValue("p"));
        } catch (Exception e) {
            proxyServerPort = DEFAULT_PROXY_SERVER_PORT;
        }
    }

    @Override
    public String toString() {
        return "CmdArgsParser{" +
                "proxyServerPort=" + proxyServerPort +
                '}';
    }
}