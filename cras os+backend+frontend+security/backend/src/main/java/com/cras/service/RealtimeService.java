package com.cras.service;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class RealtimeService {
    private final SimpMessagingTemplate messagingTemplate;

    public RealtimeService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void publish(String event, Object data) {
        messagingTemplate.convertAndSend("/topic/cras", new Event(event, data));
    }

    public record Event(String event, Object data) {}
}
