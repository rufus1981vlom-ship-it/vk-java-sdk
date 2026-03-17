package com.orda.vkplugin.piaro;

import com.orda.vkplugin.PiarOrdaPlugin;

public class PromoComposer {
    private final PiarOrdaPlugin plugin;

    public PromoComposer(PiarOrdaPlugin plugin) {
        this.plugin = plugin;
    }

    public String compose(String base, String targetVkLink) {
        String ip = plugin.getConfig().getString("piaro.server-ip", "mc.example.net");
        String globalVk = plugin.getConfig().getString("piaro.global-vk-link", "https://vk.com/example");
        String cta = plugin.getConfig().getString("piaro.footer-cta", "Заходи прямо сейчас!");
        int maxLen = plugin.getConfig().getInt("piaro.max-length", 700);

        String text = base + "\n\nIP: " + ip + "\nVK: " + (!targetVkLink.isBlank() ? targetVkLink : globalVk) + "\n" + cta;
        if (text.length() > maxLen) {
            text = text.substring(0, maxLen - 1) + "…";
        }
        return text;
    }
}
