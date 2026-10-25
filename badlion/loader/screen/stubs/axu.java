/**
 * Compile-time stand-in for {@code net.minecraft.client.gui.GuiScreen} (1.8.9
 * obfuscated name {@code axu}).
 *
 * <p>The menu is compiled against this, not against the game jar, so the build
 * needs no Minecraft on the class path. Only the member names and descriptors
 * that the real class has are declared here — the JVM links by name and
 * descriptor, so the shipped class binds to the real {@code axu} at runtime.
 *
 * <p>Provenance: MCP 9.19 {@code joined.srg}.
 * <pre>
 * CL: axu net/minecraft/client/gui/GuiScreen
 * FD: axu/j  -> mc
 * FD: axu/l  -> width
 * FD: axu/m  -> height
 * FD: axu/q  -> fontRendererObj
 * MD: axu/b ()V                    -> initGui
 * MD: axu/a (IIF)V                 -> drawScreen
 * MD: axu/a (III)V                 -> mouseClicked
 * MD: axu/a (CI)V                  -> keyTyped
 * MD: axu/d ()Z                    -> doesGuiPauseGame
 * </pre>
 *
 * <p>Never packaged into the loader jar — see {@code build.bat}, which compiles
 * these to a throwaway directory used only as {@code -classpath}.
 */
public class axu {

    protected ave j;   // mc
    public int l;      // width
    public int m;      // height
    protected avn q;   // fontRendererObj

    public void b() {
    }

    public void a(int mouseX, int mouseY, float partial) {
    }

    public void a(int mouseX, int mouseY, int button) {
    }

    public void a(char typed, int key) {
    }

    public boolean d() {
        return true;
    }
}
