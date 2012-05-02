// Copyright © 2011-2012, Esko Luontola <www.orfjackal.net>
// This software is released under the Apache License 2.0.
// The license text is at http://www.apache.org/licenses/LICENSE-2.0

package fi.jumi.actors;

import fi.jumi.actors.eventizers.*;
import fi.jumi.actors.mq.*;

import javax.annotation.concurrent.*;
import java.util.concurrent.Executor;

@ThreadSafe
public abstract class Actors {

    private final ThreadLocal<ActorThread> currentActorThread = new ThreadLocal<ActorThread>(); // TODO: remove?

    private final EventizerProvider eventizerProvider;

    public Actors(EventizerProvider eventizerProvider) {
        this.eventizerProvider = eventizerProvider;
    }

    public ActorThread startActorThread(String name) {
        checkNotInsideAnActor();
        ActorThreadImpl actorThread = new ActorThreadImpl();
        startActorThread(name, actorThread);
        return actorThread;
    }

    private void checkNotInsideAnActor() {
        if (currentActorThread.get() != null) {
            throw new IllegalStateException("already inside an actor thread");
        }
    }

    protected abstract void startActorThread(String name, MessageProcessor actorThread);


    @ThreadSafe
    private class ActorThreadImpl implements ActorThread, Executor, MessageProcessor {

        private final MessageQueue<Runnable> taskQueue = new MessageQueue<Runnable>();

        @Override
        public <T> ActorRef<T> bindActor(Class<T> type, T rawActor) {
            Eventizer<T> eventizer = eventizerProvider.getEventizerForType(type);
            T proxy = eventizer.newFrontend(new MessageToActorSender<T>(this, rawActor));
            return ActorRef.wrap(type.cast(proxy));
        }

        @Override
        public void execute(Runnable task) {
            taskQueue.send(task);
        }

        @Override
        public void processNextMessage() throws InterruptedException {
            Runnable task = taskQueue.take();
            process(task);
        }

        @Override
        public boolean processNextMessageIfAny() {
            Runnable task = taskQueue.poll();
            if (task == null) {
                return false;
            }
            process(task);
            return true;
        }

        private void process(Runnable task) {
            currentActorThread.set(this);
            try {
                task.run();
            } finally {
                currentActorThread.remove();
            }
        }
    }

    @ThreadSafe
    private static class MessageToActorSender<T> implements MessageSender<Event<T>> {
        private final Executor actorThread;
        private final T rawActor;

        public MessageToActorSender(Executor actorThread, T rawActor) {
            this.actorThread = actorThread;
            this.rawActor = rawActor;
        }

        @Override
        public void send(final Event<T> message) {
            actorThread.execute(new MessageToActor<T>(rawActor, message));
        }
    }

    @NotThreadSafe
    private static class MessageToActor<T> implements Runnable {
        private T rawActor;
        private final Event<T> message;

        public MessageToActor(T rawActor, Event<T> message) {
            this.rawActor = rawActor;
            this.message = message;
        }

        @Override
        public void run() {
            message.fireOn(rawActor);
        }

        @Override
        public String toString() {
            // TODO: write a test
            return "MessageToActor@" + hashCode() + "(" + rawActor.getClass().getName() + ", " + message + ")";
        }
    }
}
