package org.example.mpstlibrary.processor;

import org.example.mpstlibrary.data.Protocol;
import org.example.mpstlibrary.data.State;
import org.example.mpstlibrary.data.Transition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class ProtocolInterpreterTest {

    @Autowired
    private ProtocolInterpreter interpreter;
    private Protocol protocol;
}

