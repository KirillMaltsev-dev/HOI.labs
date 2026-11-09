package lab2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class ArrayStrings implements SortableStrings {
    private final List<String> values = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void addFirst(String value) throws InterruptedException {
        synchronized (values) {
            checkInterrupted();
            values.addFirst(Objects.requireNonNull(value));
        }
    }

    @Override
    public Step step(int position, long insideDelayMillis) throws InterruptedException {
        if (position < 0 || insideDelayMillis < 0) {
            throw new IllegalArgumentException("Позиция и задержка должны быть неотрицательными");
        }
        synchronized (values) {
            checkInterrupted();
            if (position >= values.size() - 1) {
                return Step.NO_PAIR;
            }
            Thread.sleep(insideDelayMillis);
            String left = values.get(position);
            String right = values.get(position + 1);
            if (left.compareTo(right) <= 0) {
                return Step.UNCHANGED;
            }
            values.set(position, right);
            values.set(position + 1, left);
            return Step.SWAPPED;
        }
    }

    @Override
    public List<String> snapshot() throws InterruptedException {
        synchronized (values) {
            checkInterrupted();
            return List.copyOf(values);
        }
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
    }
}
