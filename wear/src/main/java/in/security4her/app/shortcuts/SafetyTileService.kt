package `in`.security4her.app.shortcuts

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import `in`.security4her.app.FakeCallShortcutActivity
import `in`.security4her.app.SosShortcutActivity

/** Static actions only: rendering or refreshing this tile never sends a command. */
class SafetyTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val content = LayoutElementBuilders.Column.Builder()
            .addContent(text("Security4Her", 14f))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(8f)).build())
            .addContent(button("SOS · 5 sec", "Start cancellable SOS countdown", SosShortcutActivity::class.java.name, 0xFFB71C1C.toInt()))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(8f)).build())
            .addContent(button("Fake call", "Request fake call on phone", FakeCallShortcutActivity::class.java.name, 0xFF5B2A86.toInt()))
            .build()
        val root = LayoutElementBuilders.Box.Builder().setWidth(expand()).setHeight(expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder()
                .setBackground(ModifiersBuilders.Background.Builder().setColor(argb(0xFF000000.toInt())).build()).build())
            .addContent(content).build()
        val tile = TileBuilders.Tile.Builder().setResourcesVersion("1")
            .setTileTimeline(TimelineBuilders.Timeline.Builder().addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder()
                    .setLayout(LayoutElementBuilders.Layout.Builder().setRoot(root).build()).build()).build())
            .build()
        return ready(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        ready(ResourceBuilders.Resources.Builder().setVersion("1").build())

    private fun text(label: String, size: Float) = LayoutElementBuilders.Text.Builder().setText(label)
        .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(size)).setColor(argb(0xFFFFFFFF.toInt())).build()).build()

    private fun button(label: String, description: String, activity: String, color: Int): LayoutElementBuilders.Box {
        val action = ActionBuilders.LaunchAction.Builder().setAndroidActivity(
            ActionBuilders.AndroidActivity.Builder().setPackageName(packageName).setClassName(activity).build()).build()
        return LayoutElementBuilders.Box.Builder().setWidth(dp(138f)).setHeight(dp(48f))
            .setModifiers(ModifiersBuilders.Modifiers.Builder()
                .setClickable(ModifiersBuilders.Clickable.Builder().setId(activity).setOnClick(action).build())
                .setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription(description).build())
                .setBackground(ModifiersBuilders.Background.Builder().setColor(argb(color))
                    .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(24f)).build()).build()).build())
            .addContent(text(label, 16f)).build()
    }

    private fun <T> ready(value: T): ListenableFuture<T> = CallbackToFutureAdapter.getFuture { completer ->
        completer.set(value)
        "Security4Her tile"
    }
}
