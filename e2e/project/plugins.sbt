resolvers += Resolver.jcenterRepo

val defaultPluginVersion = "0.5-SNAPSHOT"

val pluginVersion = sys.props.get("plugin.version").getOrElse(defaultPluginVersion)

addSbtPlugin("eu.swdev" % """sbt-scala-ts""" % pluginVersion)
