package net.ravendb.client.primitives;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;


public class EventHelper {

    /**
     * Helper used for invoking event on list of delegates
     *
     * @param delegates
     * @param sender
     * @param event
     */
    public static <T extends EventArgs> void invoke(List<EventHandler<T>> delegates, Object sender, T event) {
        for (EventHandler<T> delegate : delegates) {
            delegate.handle(sender, event);
        }
    }

    public static <T> void invoke(List<Consumer<T>> actions, T argument) {
        for (Consumer<T> action : actions) {
            action.accept(argument);
        }
    }
}