package ordacraft.vk.service;

import ordacraft.vk.config.ChatMode;
import ordacraft.vk.config.PluginSettings;
import ordacraft.vk.vk.api.VkApiClient;

public class EventRelayService {
    private final PluginSettings settings;
    private final VkApiClient api;
    public EventRelayService(PluginSettings settings, VkApiClient api){ this.settings=settings; this.api=api; }
    public void event(String text){
        settings.chats().stream().filter(c->c.mode()== ChatMode.EVENTS).forEach(c->{ try { api.send(c.id(), text);} catch (Exception ignored) {} });
    }
}
