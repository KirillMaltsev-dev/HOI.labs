package ru.nsu.maltsev.j3;

import java.io.IOException;
import java.net.URI;

@FunctionalInterface
interface PageClient {
    Page fetch(URI uri) throws IOException, InterruptedException;
}
