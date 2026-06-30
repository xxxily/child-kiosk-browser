package site.anzz.childkiosk.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.ui.graphics.vector.ImageVector

data class BuiltInWebAppIcon(
    val id: String,
    val vector: ImageVector,
    val label: String
)

val BuiltInWebAppIcons = listOf(
    BuiltInWebAppIcon("icon_gamepad", Icons.Default.SportsEsports, "游戏"),
    BuiltInWebAppIcon("icon_videogame", Icons.Default.VideogameAsset, "电玩"),
    BuiltInWebAppIcon("icon_rocket", Icons.Default.Star, "星标"),
    BuiltInWebAppIcon("icon_puzzle", Icons.Default.Extension, "拼图"),
    BuiltInWebAppIcon("icon_book", Icons.Default.MenuBook, "图书"),
    BuiltInWebAppIcon("icon_story", Icons.Default.AutoStories, "故事"),
    BuiltInWebAppIcon("icon_article", Icons.Default.Article, "文章"),
    BuiltInWebAppIcon("icon_paint", Icons.Default.Palette, "调色"),
    BuiltInWebAppIcon("icon_brush", Icons.Default.Brush, "画笔"),
    BuiltInWebAppIcon("icon_draw", Icons.Default.Draw, "绘画"),
    BuiltInWebAppIcon("icon_color", Icons.Default.ColorLens, "色彩"),
    BuiltInWebAppIcon("icon_pet", Icons.Default.Pets, "动物"),
    BuiltInWebAppIcon("icon_music", Icons.Default.MusicNote, "音乐"),
    BuiltInWebAppIcon("icon_movie", Icons.Default.Movie, "电影"),
    BuiltInWebAppIcon("icon_video", Icons.Default.VideoLibrary, "视频"),
    BuiltInWebAppIcon("icon_theater", Icons.Default.TheaterComedy, "表演"),
    BuiltInWebAppIcon("icon_school", Icons.Default.School, "学校"),
    BuiltInWebAppIcon("icon_science", Icons.Default.Science, "科学"),
    BuiltInWebAppIcon("icon_lightbulb", Icons.Default.Lightbulb, "灵感"),
    BuiltInWebAppIcon("icon_calculate", Icons.Default.Calculate, "计算"),
    BuiltInWebAppIcon("icon_translate", Icons.Default.Translate, "语言"),
    BuiltInWebAppIcon("icon_code", Icons.Default.Code, "编程"),
    BuiltInWebAppIcon("icon_computer", Icons.Default.Computer, "电脑"),
    BuiltInWebAppIcon("icon_toy", Icons.Default.Face, "笑脸"),
    BuiltInWebAppIcon("icon_robot", Icons.Default.SmartToy, "机器人"),
    BuiltInWebAppIcon("icon_gift", Icons.Default.Favorite, "收藏"),
    BuiltInWebAppIcon("icon_home", Icons.Default.Home, "主页"),
    BuiltInWebAppIcon("icon_public", Icons.Default.Public, "网站"),
    BuiltInWebAppIcon("icon_language", Icons.Default.Language, "浏览"),
    BuiltInWebAppIcon("icon_explore", Icons.Default.Explore, "探索"),
    BuiltInWebAppIcon("icon_travel", Icons.Default.TravelExplore, "发现"),
    BuiltInWebAppIcon("icon_map", Icons.Default.Map, "地图"),
    BuiltInWebAppIcon("icon_camera", Icons.Default.CameraAlt, "相机"),
    BuiltInWebAppIcon("icon_photo", Icons.Default.PhotoCamera, "图片"),
    BuiltInWebAppIcon("icon_tool", Icons.Default.Construction, "工具"),
    BuiltInWebAppIcon("icon_widgets", Icons.Default.Widgets, "组件"),
    BuiltInWebAppIcon("icon_workspaces", Icons.Default.Workspaces, "空间")
)

fun getIconVector(iconName: String?): ImageVector {
    return BuiltInWebAppIcons.firstOrNull { it.id == iconName }?.vector ?: Icons.Default.Star
}
