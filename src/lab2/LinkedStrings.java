package lab2;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

public final class LinkedStrings implements SortableStrings {
    private static final class Node {
        private final String value;
        private final ReentrantLock lock = new ReentrantLock();
        private Node next;

        private Node(String value) {
            this.value = value;
        }
    }

    private final Node head = new Node(null);

    @Override
    public void addFirst(String value) throws InterruptedException {
        Node node = new Node(Objects.requireNonNull(value));
        head.lock.lockInterruptibly();
        try {
            node.next = head.next;
            head.next = node;
        } finally {
            head.lock.unlock();
        }
    }

    @Override
    public Step step(int position, long insideDelayMillis) throws InterruptedException {
        if (position < 0 || insideDelayMillis < 0) {
            throw new IllegalArgumentException("Позиция и задержка должны быть неотрицательными");
        }
        List<Node> held = new ArrayList<>(3);
        try {
            acquire(head, held);
            Node previous = head;
            for (int i = 0; i < position; i++) {
                Node next = previous.next;
                if (next == null) {
                    return Step.NO_PAIR;
                }
                acquire(next, held);
                previous.lock.unlock();
                held.removeFirst();
                previous = next;
            }
            Node left = previous.next;
            if (left == null) {
                return Step.NO_PAIR;
            }
            acquire(left, held);
            Node right = left.next;
            if (right == null) {
                return Step.NO_PAIR;
            }
            acquire(right, held);
            Thread.sleep(insideDelayMillis);
            if (left.value.compareTo(right.value) <= 0) {
                return Step.UNCHANGED;
            }
            previous.next = right;
            left.next = right.next;
            right.next = left;
            return Step.SWAPPED;
        } finally {
            release(held);
        }
    }

    @Override
    public List<String> snapshot() throws InterruptedException {
        List<Node> held = new ArrayList<>();
        try {
            acquire(head, held);
            Node current = head.next;
            while (current != null) {
                acquire(current, held);
                current = current.next;
            }
            List<String> values = new ArrayList<>(held.size() - 1);
            for (int i = 1; i < held.size(); i++) {
                values.add(held.get(i).value);
            }
            return List.copyOf(values);
        } finally {
            release(held);
        }
    }

    private static void acquire(Node node, List<Node> held) throws InterruptedException {
        node.lock.lockInterruptibly();
        held.add(node);
    }

    private static void release(List<Node> held) {
        for (int i = held.size() - 1; i >= 0; i--) {
            held.get(i).lock.unlock();
        }
    }
}
