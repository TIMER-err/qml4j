import QtQuick
import md3.Core

// MD3 Style ScrollBar
Rectangle {
    id: scrollBarTrack
    
    // API
    property Flickable target: null
    property int orientation: Qt.Vertical // Qt.Vertical or Qt.Horizontal
    // PullToRefresh can reserve leading travel before its resting position.
    property real contentStart: 0
    
    // Styling
    property color trackColor: "transparent"
    property color thumbColor: Theme.color.outline
    property color thumbPressedColor: Theme.color.primary
    property real thumbOpacity: 0.8
    property real fadeDuration: 200
    
    // Dimensions
    implicitWidth: orientation === Qt.Vertical ? (isPressed ? 4 : 8) : parent.width
    implicitHeight: orientation === Qt.Vertical ? parent.height : (isPressed ? 4 : 8)
    
    readonly property real viewportSize: !target ? 0
        : (orientation === Qt.Vertical ? target.height : target.width)
    readonly property real contentSize: !target ? 0
        : (orientation === Qt.Vertical ? target.contentHeight : target.contentWidth)
    readonly property real maximumContentPosition: Math.max(contentStart,
        contentSize - viewportSize)
    readonly property real scrollRange: Math.max(0, maximumContentPosition - contentStart)
    readonly property real contentPosition: !target ? contentStart
        : (orientation === Qt.Vertical ? target.contentY : target.contentX)
    visible: target && scrollRange > 0
    color: trackColor

    function setContentPosition(value) {
        if (!target) return
        var bounded = Math.max(contentStart, Math.min(value, maximumContentPosition))
        if (orientation === Qt.Vertical) target.contentY = bounded
        else target.contentX = bounded
        target.syncTarget()
    }
    
    // Internal state
    property bool isPressed: scrollMouseArea.pressed
    property bool isMoving: target && target.moving
    property bool isHovered: scrollMouseArea.containsMouse
    
    Behavior on implicitWidth { NumberAnimation { duration: 200; easing.type: Easing.OutQuad } }
    Behavior on implicitHeight { NumberAnimation { duration: 200; easing.type: Easing.OutQuad } }

    // An inert drag target keeps capture even outside the thumb and prevents the
    // underlying Flickable from stealing this direct manipulation.
    Item {
        id: dragProxy
        visible: false
        x: 0
        y: 0
    }

    // Thumb Component
    Rectangle {
        id: scrollBarThumb
        
        // Dimensions & Position
        x: orientation === Qt.Vertical ? (parent.width - width) / 2 : calculatePosition()
        y: orientation === Qt.Vertical ? calculatePosition() : (parent.height - height) / 2
        
        width: orientation === Qt.Vertical ? (isPressed ? 4 : 6) : calculateSize()
        height: orientation === Qt.Vertical ? calculateSize() : (isPressed ? 4 : 6)
        
        color: isPressed ? thumbPressedColor : thumbColor
        radius: (orientation === Qt.Vertical ? width : height) / 2
        
        opacity: (isHovered || isPressed || isMoving) ? thumbOpacity : 0.0
        
        Behavior on opacity { NumberAnimation { duration: fadeDuration } }
        Behavior on color { ColorAnimation { duration: 150 } }
        Behavior on width { NumberAnimation { duration: 200; easing.type: Easing.OutQuad } }
        Behavior on height { NumberAnimation { duration: 200; easing.type: Easing.OutQuad } }

        function calculateSize() {
            if (!target) return 40
            var availableContent = Math.max(viewportSize, contentSize - contentStart)
            var ratio = availableContent > 0 ? viewportSize / availableContent : 1
            var trackSize = orientation === Qt.Vertical
                ? scrollBarTrack.height : scrollBarTrack.width
            return Math.max(40, ratio * trackSize) + (isPressed ? 10 : 0)
        }
        
        function calculatePosition() {
            if (!target || scrollRange <= 0) return 0
            var trackSize = orientation === Qt.Vertical
                ? scrollBarTrack.height : scrollBarTrack.width
            var thumbSize = orientation === Qt.Vertical ? height : width
            var maxThumbPosition = Math.max(0, trackSize - thumbSize)
            var scrollRatio = (contentPosition - contentStart) / scrollRange
            return Math.max(0,
                Math.min(scrollRatio * maxThumbPosition, maxThumbPosition))
        }
    }

    MouseArea {
        id: scrollMouseArea
        anchors.fill: parent
        hoverEnabled: true
        preventStealing: true
        drag.target: dragProxy
        drag.axis: orientation === Qt.Vertical ? "YAxis" : "XAxis"
        drag.minimumX: -100000
        drag.maximumX: 100000
        drag.minimumY: -100000
        drag.maximumY: 100000
        
        property real pressedPos: 0
        property real initialContentPos: 0
        // The pressed thumb animates its length; never change the drag conversion.
        property real grabbedThumbTravel: 0
        property real grabbedScrollRange: 0
        
        onPressed: (mouse) => {
            if (!target) return
            target.stopScroll()
            dragProxy.x = 0
            dragProxy.y = 0

            var mousePos = orientation === Qt.Vertical ? mouse.y : mouse.x
            var thumbPos = orientation === Qt.Vertical
                ? scrollBarThumb.y : scrollBarThumb.x
            var thumbSize = orientation === Qt.Vertical
                ? scrollBarThumb.height : scrollBarThumb.width
            var trackSize = orientation === Qt.Vertical
                ? scrollBarTrack.height : scrollBarTrack.width
            grabbedThumbTravel = Math.max(0, trackSize - thumbSize)
            grabbedScrollRange = scrollBarTrack.scrollRange

            if (mousePos < thumbPos || mousePos > thumbPos + thumbSize) {
                if (grabbedThumbTravel > 0) {
                    var clickRatio = Math.max(0,
                        Math.min((mousePos - thumbSize / 2) / grabbedThumbTravel, 1))
                    scrollBarTrack.setContentPosition(
                        scrollBarTrack.contentStart + clickRatio * grabbedScrollRange)
                }
            }
            pressedPos = mousePos
            // The derived contentPosition is stale until DirtyQueue flushes.
            initialContentPos = orientation === Qt.Vertical ? target.contentY : target.contentX
        }
        
        onPositionChanged: (mouse) => {
            if (!pressed || !target) return
            var mousePos = orientation === Qt.Vertical ? mouse.y : mouse.x
            var delta = mousePos - pressedPos
            if (grabbedThumbTravel > 0) {
                scrollBarTrack.setContentPosition(initialContentPos
                    + delta / grabbedThumbTravel * grabbedScrollRange)
            }
        }
    }
}
