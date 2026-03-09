package ordacraft.vk.vk.polling;

import ordacraft.vk.vk.model.VkIncomingMessage;

import java.util.function.Consumer;

public class VkLongPollService {
    private final Consumer<VkIncomingMessage> consumer;
    public VkLongPollService(Consumer<VkIncomingMessage> consumer){ this.consumer = consumer; }
    public void start() {}
    public void stop() {}
    public void simulateIncoming(VkIncomingMessage message){ consumer.accept(message); }
}
