package it.belloworld.mercurygram.compat.gms.wearable;

import it.belloworld.mercurygram.compat.gms.tasks.Task;

/** Stub MessageClient — GMS Wearable removed in FOSS builds. */
public class MessageClient {

    public Task<Integer> sendMessage(String nodeId, String path, byte[] data) {
        return new Task<Integer>() { };
    }
}
