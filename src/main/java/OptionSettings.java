import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class OptionSettings {

    private final String opt;
    private final String longOpt;
    private final Boolean hasArg;
    private final String description;
}