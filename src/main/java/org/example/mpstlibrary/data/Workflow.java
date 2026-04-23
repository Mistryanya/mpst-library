package org.example.mpstlibrary.data;

import lombok.Data;

import java.util.LinkedList;
import java.util.List;

@Data
public class Workflow {

    String name;
    String description;
    String owner;
    LinkedList<State> states;
}
