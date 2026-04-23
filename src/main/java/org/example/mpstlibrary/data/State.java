package org.example.mpstlibrary.data;

import lombok.Data;

import java.io.Serializable;
import java.util.LinkedList;

@Data
public class State implements Serializable {
    private Boolean start = false;
    private Boolean end = false;
    private LinkedList<Transition> transitions;
    public String name;

}