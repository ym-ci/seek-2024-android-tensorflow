package ca.ymci.seekbs;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
public class ControlFrame {
    private final byte speed_left;
    private final byte direction_left;
    private final byte speed_right;
    private final byte direction_right;
}
