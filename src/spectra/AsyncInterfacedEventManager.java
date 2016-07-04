/*
 * Copyright 2016 John Grosh (jagrosh).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package spectra;

import net.dv8tion.jda.entities.impl.JDAImpl;
import net.dv8tion.jda.events.Event;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.dv8tion.jda.hooks.EventListener;
import net.dv8tion.jda.hooks.IEventManager;

/**
 *
 * @author John Grosh (jagrosh)
 */


public class AsyncInterfacedEventManager implements IEventManager
{
    private final CopyOnWriteArrayList<EventListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService threadpool;

    public AsyncInterfacedEventManager()
    {
        threadpool = Executors.newCachedThreadPool();
    }

    @Override
    public void register(Object listener)
    {
        if (!(listener instanceof EventListener))
        {
            throw new IllegalArgumentException("Listener must implement EventListener");
        }
        listeners.add(((EventListener) listener));
    }

    @Override
    public void unregister(Object listener)
    {
        if(listener instanceof EventListener)
            listeners.remove((EventListener)listener);
    }

    @Override
    public List<Object> getRegisteredListeners()
    {
        return Collections.unmodifiableList(new LinkedList<>(listeners));
    }

    @Override
    public void handle(Event event)
    {
        listeners.stream().forEach((listener) -> {
            try
            {
                threadpool.submit( () -> listener.onEvent(event) );
            }
            catch (Throwable throwable)
            {
                JDAImpl.LOG.fatal("One of the EventListeners had an uncaught exception");
                JDAImpl.LOG.log(throwable);
            }
        });
    }
    
    public void shutdown()
    {
        threadpool.shutdown();
    }
}
