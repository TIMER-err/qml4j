package io.github.timer_err.qml4j.render;

import io.github.timer_err.qml4j.render.items.animation.Behavior;
import io.github.timer_err.qml4j.render.items.layout.Column;
import io.github.timer_err.qml4j.render.items.view.Component;
import io.github.timer_err.qml4j.render.items.view.Connections;
import io.github.timer_err.qml4j.render.items.core.Binding;
import io.github.timer_err.qml4j.render.items.core.Canvas;
import io.github.timer_err.qml4j.render.items.core.Flickable;
import io.github.timer_err.qml4j.render.items.input.FocusScope;
import io.github.timer_err.qml4j.render.items.core.Gradient;
import io.github.timer_err.qml4j.render.items.effect.ColorOverlay;
import io.github.timer_err.qml4j.render.items.effect.DropShadow;
import io.github.timer_err.qml4j.render.items.layout.Flow;
import io.github.timer_err.qml4j.render.items.layout.GridLayout;
import io.github.timer_err.qml4j.render.items.effect.Glow;
import io.github.timer_err.qml4j.render.items.shape.PathArc;
import io.github.timer_err.qml4j.render.items.shape.PathCubic;
import io.github.timer_err.qml4j.render.items.shape.PathLine;
import io.github.timer_err.qml4j.render.items.shape.PathMove;
import io.github.timer_err.qml4j.render.items.shape.PathQuad;
import io.github.timer_err.qml4j.render.items.shape.Shape;
import io.github.timer_err.qml4j.render.items.shape.ShapePath;
import io.github.timer_err.qml4j.render.items.core.GradientStop;
import io.github.timer_err.qml4j.render.items.view.GridView;
import io.github.timer_err.qml4j.render.items.core.Image;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.render.items.view.ListElement;
import io.github.timer_err.qml4j.render.items.view.ListModel;
import io.github.timer_err.qml4j.render.items.view.ListView;
import io.github.timer_err.qml4j.render.items.view.Loader;
import io.github.timer_err.qml4j.render.items.core.MouseArea;
import io.github.timer_err.qml4j.render.items.animation.ColorAnimation;
import io.github.timer_err.qml4j.render.items.animation.NumberAnimation;
import io.github.timer_err.qml4j.render.items.animation.OpacityAnimation;
import io.github.timer_err.qml4j.render.items.animation.ParallelAnimation;
import io.github.timer_err.qml4j.render.items.animation.PauseAnimation;
import io.github.timer_err.qml4j.render.items.animation.PropertyAnimation;
import io.github.timer_err.qml4j.render.items.animation.ScriptAction;
import io.github.timer_err.qml4j.render.items.animation.SequentialAnimation;
import io.github.timer_err.qml4j.render.items.animation.PropertyChanges;
import io.github.timer_err.qml4j.render.items.animation.RotationAnimation;
import io.github.timer_err.qml4j.render.items.core.Rectangle;
import io.github.timer_err.qml4j.render.items.view.Repeater;
import io.github.timer_err.qml4j.render.items.layout.Row;
import io.github.timer_err.qml4j.render.items.layout.RowLayout;
import io.github.timer_err.qml4j.render.items.layout.ColumnLayout;
import io.github.timer_err.qml4j.render.items.layout.StackLayout;
import io.github.timer_err.qml4j.render.items.effect.MultiEffect;
import io.github.timer_err.qml4j.render.items.effect.ShaderEffectSource;
import io.github.timer_err.qml4j.render.items.transform.Rotation;
import io.github.timer_err.qml4j.render.items.transform.Scale;
import io.github.timer_err.qml4j.render.items.transform.Translate;
import io.github.timer_err.qml4j.render.items.dialog.FileDialog;
import io.github.timer_err.qml4j.render.items.handler.TapHandler;
import io.github.timer_err.qml4j.render.items.handler.DragHandler;
import io.github.timer_err.qml4j.render.items.animation.State;
import io.github.timer_err.qml4j.render.items.core.Text;
import io.github.timer_err.qml4j.render.items.input.TextEdit;
import io.github.timer_err.qml4j.render.items.input.TextInput;
import io.github.timer_err.qml4j.render.items.animation.Timer;
import io.github.timer_err.qml4j.render.items.animation.Transition;
import io.github.timer_err.qml4j.render.items.window.Window;
import io.github.timer_err.qml4j.render.items.window.ApplicationWindow;
import io.github.timer_err.qml4j.render.items.window.AbstractButton;
import io.github.timer_err.qml4j.render.items.window.Button;
import io.github.timer_err.qml4j.render.items.window.Control;
import io.github.timer_err.qml4j.render.items.window.Label;
import io.github.timer_err.qml4j.render.items.input.TextField;
import io.github.timer_err.qml4j.engine.QtObject;
import io.github.timer_err.qml4j.runtime.color.StyleManager;

import io.github.timer_err.qml4j.compiler.TypeRegistry;

public final class StockTypes {

    private StockTypes() {}

    public static TypeRegistry registry() {
        return new TypeRegistry()
            .register("Item", Item.class)
            .register("Rectangle", Rectangle.class)
            .register("Text", Text.class)
            .register("Column", Column.class)
            .register("Row", Row.class)
            .register("RowLayout", RowLayout.class)
            .register("ColumnLayout", ColumnLayout.class)
            .register("StackLayout", StackLayout.class)
            .register("GridLayout", GridLayout.class)
            .register("Flow", Flow.class)
            .register("MultiEffect", MultiEffect.class)
            .register("ShaderEffectSource", ShaderEffectSource.class)
            .register("Translate", Translate.class)
            .register("Rotation", Rotation.class)
            .register("Scale", Scale.class)
            .register("FileDialog", FileDialog.class)
            .register("TapHandler", TapHandler.class)
            .register("DragHandler", DragHandler.class)
            .register("Timer", Timer.class)
            .register("MouseArea", MouseArea.class)
            .register("Image", Image.class)
            .register("Loader", Loader.class)
            .register("PropertyAnimation", PropertyAnimation.class)
            .register("NumberAnimation", NumberAnimation.class)
            .register("ColorAnimation", ColorAnimation.class)
            .register("RotationAnimation", RotationAnimation.class)
            .register("OpacityAnimation", OpacityAnimation.class)
            .register("ParallelAnimation", ParallelAnimation.class)
            .register("PauseAnimation", PauseAnimation.class)
            .register("ScriptAction", ScriptAction.class)
            .register("SequentialAnimation", SequentialAnimation.class)
            .register("State", State.class)
            .register("PropertyChanges", PropertyChanges.class)
            .register("Transition", Transition.class)
            .register("Behavior", Behavior.class)
            .register("Repeater", Repeater.class)
            .register("ListModel", ListModel.class)
            .register("ListElement", ListElement.class)
            .register("ListView", ListView.class)
            .register("GridView", GridView.class)
            .register("Component", Component.class)
            .register("Connections", Connections.class)
            .register("Flickable", Flickable.class)
            .register("Canvas", Canvas.class)
            .register("Binding", Binding.class)
            .register("FocusScope", FocusScope.class)
            .register("Shape", Shape.class)
            .register("ShapePath", ShapePath.class)
            .register("PathMove", PathMove.class)
            .register("PathLine", PathLine.class)
            .register("PathQuad", PathQuad.class)
            .register("PathCubic", PathCubic.class)
            .register("PathArc", PathArc.class)
            .register("DropShadow", DropShadow.class)
            .register("Glow", Glow.class)
            .register("ColorOverlay", ColorOverlay.class)
            .register("Gradient", Gradient.class)
            .register("GradientStop", GradientStop.class)
            .register("TextInput", TextInput.class)
            .register("TextEdit", TextEdit.class)
            .register("Window", Window.class)
            .register("ApplicationWindow", ApplicationWindow.class)
            .register("Control", Control.class)
            .register("AbstractButton", AbstractButton.class)
            .register("Button", Button.class)
            .register("Label", Label.class)
            .register("TextField", TextField.class)
            .register("QtObject", QtObject.class)
            .registerSingleton("StyleManager", StyleManager.class);
    }

    /** Stock visual types without host filesystem/window facilities or shared
     * process-wide style state. Intended for third-party QML documents. */
    public static TypeRegistry safeRegistry() {
        return registry()
                .unregister("FileDialog")
                .unregister("Window")
                .unregister("ApplicationWindow")
                .unregister("StyleManager");
    }
}
