package ru.nsu.maltsev.j3;

import java.util.List;
import java.util.Objects;

record Page(String message, List<String> successors) {
    Page {
        Objects.requireNonNull(message);
        successors = List.copyOf(successors);
    }
}
