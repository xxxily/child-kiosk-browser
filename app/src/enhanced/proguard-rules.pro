# AMap Location SDK 11.2.000 references optional classes that are not packaged
# with the public Maven artifact. Suppress R8 missing-class warnings for the
# optional paths only in the enhanced flavor.
-dontwarn com.amap.ams.gnss.GnssSoftLocator
-dontwarn net.jafama.FastMath
