package fx.sniper.gui;

import fx.sniper.TargetManager;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;

import java.io.IOException;

/** Target list editor. Opened with the hotkey wired in SniperMod. */
public final class SniperGui extends GuiScreen {

    private final TargetManager targets;

    private GuiTextField input;
    private String status = "";

    public SniperGui(TargetManager targets) {
        this.targets = targets;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        input = new GuiTextField(0, fontRendererObj, width / 2 - 100, height / 2 - 40, 200, 20);
        input.setMaxStringLength(16);
        input.setFocused(true);

        buttonList.clear();
        buttonList.add(new GuiButton(1, width / 2 - 100, height / 2 - 10, 95, 20, "Add"));
        buttonList.add(new GuiButton(2, width / 2 + 5, height / 2 - 10, 95, 20, "Remove"));
        buttonList.add(new GuiButton(3, width / 2 - 100, height / 2 + 20, 200, 20,
            "Auto-join: " + (targets.config().autoJoin ? "ON" : "OFF")));
        buttonList.add(new GuiButton(4, width / 2 - 100, height / 2 + 45, 200, 20, "Close"));
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        switch (b.id) {
            case 1:
                status = targets.add(input.getText())
                    ? "added " + input.getText()
                    : "duplicate or empty";
                input.setText("");
                break;
            case 2:
                status = targets.remove(input.getText())
                    ? "removed " + input.getText()
                    : "not in list";
                input.setText("");
                break;
            case 3:
                targets.config().autoJoin = !targets.config().autoJoin;
                targets.config().save();
                b.displayString = "Auto-join: " + (targets.config().autoJoin ? "ON" : "OFF");
                break;
            case 4:
                mc.displayGuiScreen(null);
                break;
            default:
                break;
        }
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (input.textboxKeyTyped(c, key)) return;
        if (key == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(c, key);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partial) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, "\u00a7lBedwars Sniper", width / 2, height / 2 - 70, 0xFFFFFF);
        input.drawTextBox();
        drawCenteredString(fontRendererObj,
            "\u00a77targets: " + targets.snapshot().size() + "  |  " + status,
            width / 2, height / 2 + 75, 0xAAAAAA);
        super.drawScreen(mouseX, mouseY, partial);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }
}
