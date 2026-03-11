package ordacraft.vk.service;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public class ConsoleDispatchService {
    private final Plugin plugin;
    public ConsoleDispatchService(Plugin plugin){ this.plugin = plugin; }
    public void dispatch(String command){ Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)); }
    public boolean dispatchImmediate(String command){ return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command); }
}
