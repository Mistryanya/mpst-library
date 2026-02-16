package org.example.mpstlibrary.data;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
@AllArgsConstructor
public class Transition implements Serializable {

    // "on" maps to the event that triggers the transition
    private String on;

    // "to" maps to the target state
    private String to;

}
