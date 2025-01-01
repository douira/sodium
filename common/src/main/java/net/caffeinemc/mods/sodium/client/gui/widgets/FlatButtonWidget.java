package net.caffeinemc.mods.sodium.client.gui.widgets;

import net.caffeinemc.mods.sodium.client.gui.ButtonTheme;
import net.caffeinemc.mods.sodium.client.gui.Colors;
import net.caffeinemc.mods.sodium.client.gui.Layout;
import net.caffeinemc.mods.sodium.client.util.Dim2i;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.navigation.CommonInputs;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

public class FlatButtonWidget extends AbstractWidget implements Renderable {
    private static final ButtonTheme DEFAULT_THEME = new ButtonTheme(
            0xFFFFFFFF, 0xFFFFFFFF, 0x90FFFFFF,
            0xE0000000, 0x90000000, 0x60000000);

    private final Runnable action;
    private final boolean drawBackground;
    private final boolean leftAlign;
    private final ButtonTheme theme;
    private final Component label;

    private boolean selected;
    private boolean enabled = true;
    private boolean visible = true;


    public FlatButtonWidget(Dim2i dim, Component label, Runnable action, boolean drawBackground, boolean leftAlign, ButtonTheme theme) {
        super(dim);
        this.label = label;
        this.action = action;
        this.drawBackground = drawBackground;
        this.leftAlign = leftAlign;
        this.theme = theme;
    }

    public FlatButtonWidget(Dim2i dim, Component label, Runnable action, boolean drawBackground, boolean leftAlign) {
        this(dim, label, action, drawBackground, leftAlign, DEFAULT_THEME);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        if (!this.visible) {
            return;
        }

        this.hovered = this.isMouseOver(mouseX, mouseY);

        int backgroundColor = this.enabled ? (this.hovered ? this.theme.bgHighlight : this.theme.bgDefault) : this.theme.bgInactive;
        int textColor = this.enabled ? this.theme.themeLighter : this.theme.themeDarker;

        int strWidth = this.font.width(this.label);

        if (this.drawBackground) {
            this.drawRect(graphics, this.getX(), this.getY(), this.getLimitX(), this.getLimitY(), backgroundColor);
        }

        this.drawString(graphics, this.label, this.leftAlign ? this.getX() + Layout.TEXT_LEFT_PADDING : (this.getCenterX() - (strWidth / 2)), this.getCenterY() - 4, textColor);

        if (this.enabled && this.selected) {
            this.drawRect(graphics, this.getX(), this.getLimitY() - 1, this.getLimitX(), this.getLimitY(), Colors.THEME);
        }

        if (!this.drawBackground) {
            this.drawBorder(graphics, this.getX(), this.getY(), this.getLimitX(), this.getLimitY(), 0x8000FFEE);

        }
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.enabled || !this.visible) {
            return false;
        }

        if (button == 0 && this.isMouseOver(mouseX, mouseY)) {
            doAction();

            return true;
        }

        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!this.isFocused())
            return false;

        if (CommonInputs.selected(keyCode)) {
            doAction();
            return true;
        }

        return false;
    }

    private void doAction() {
        this.action.run();
        this.playClickSound();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    @Override
    public @Nullable ComponentPath nextFocusPath(FocusNavigationEvent event) {
        if (!this.enabled || !this.visible)
            return null;
        return super.nextFocusPath(event);
    }
}
