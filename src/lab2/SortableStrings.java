package lab2;

import java.util.Iterator;
import java.util.List;

public interface SortableStrings extends Iterable<String> {
    enum Step { NO_PAIR, UNCHANGED, SWAPPED }

    void addFirst(String value) throws InterruptedException;

    Step step(int position, long insideDelayMillis) throws InterruptedException;

    List<String> snapshot() throws InterruptedException;

    @Override
    default Iterator<String> iterator() {
        try {
            return snapshot().iterator();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Получение снимка прервано", e);
        }
    }
}
