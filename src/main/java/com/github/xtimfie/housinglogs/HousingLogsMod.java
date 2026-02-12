package com.github.xtimfie.housinglogs;


import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;

@Mod(modid = "housinglogs", name = "HousingLogs", version = "1.0.1", clientSideOnly = true)
public class HousingLogsMod {

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        ClientCommandHandler.instance.registerCommand(new CommandBlockAudit());
        ProtoolsAutomation.init();
        MinecraftForge.EVENT_BUS.register(new BlockAuditHighlightRenderer());
        BlockAuditManager.loadAreaFromDisk();
    }
}