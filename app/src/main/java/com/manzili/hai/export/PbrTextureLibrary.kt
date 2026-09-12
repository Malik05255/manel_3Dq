package com.manzili.hai.export

import java.util.Base64

/** Tiny offline textures embedded into GLB so PBR rendering is deterministic. */
object PbrTextureLibrary {
    data class Texture(val name:String,val mimeType:String="image/png",val bytes:ByteArray)

    private const val PLASTER="iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAjElEQVR42iXOy23AMAwEUZEryKTt/utKBzmlA8s/LsgcfB88jPz9/rj78zwiMsa473tZlqpSd4+IqhKR931JZqaI6JyTJMnWWmttXVcA13WpuwMwMwCZWVXHcZhZjwhV/YTeO8lt20gqAJJjDHcnKSKtNf1aABEx5zSzqjrPk2QHoKqZue97Zn6vIvIPBmhfwFyVzD4AAAAASUVORK5CYII="
    private const val STONE="iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAo0lEQVR42iXKO46FMAwAQMdy8iAyBIHE5fcme4i9BR0fy0UiExFesVOP+/v9KaWoKjPHGPd9n6ZJRNDMVNU5R0TXdfV9771nZjzPc55nRAwhLMtiZq211hp570MIXdfVWkVkGAYReZ4HmXnbtnEc/yMippQQkUSEmY/juO87pQQARISIuK7r5/MxM+fc+74551orAJCq5pxjjLVWMwMAMyulfAGmKl6m7rwiVgAAAABJRU5ErkJggg=="
    private const val WOOD="iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAnElEQVR42j3LQY6FIAwAUFoJJSrRiMeZy/z7/MuSChbBMotJZv8efD8/0zSVUhBx3/fWGjMTEc7z3Hvfti2EYIxR1daac8723nPOY4z7vmOMABBjHGPY933P8/zrIlJrJaLruhAAmHlZFhFxzh3HYa313mOt1RjzD0spABBCQCJCRGutiHjvn+dRVWbGnDMRpZTWdQUAVU0ptdZ+AY5UXmSj+za0AAAAAElFTkSuQmCC"
    private const val ROOF="iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAjUlEQVR42i2OSw6DMAxE/cMSsRDc/wJdVj0agiRAwO4iLGdGT2/w9/2c5ykiiNhai4hxHHPO4u7MzMw554h4nmcYhoiQfd+ZubWGiPd9q2pvaJqmjqsqMwPAsizuTrVWM9u2jYiIqJvmeSYzO45DVQEgpdS3dV3J3XsGgOu6ELHW+srdXUT6t1KKmQHAH7dXUzuNcBBRAAAAAElFTkSuQmCC"

    fun textures():List<Texture> = listOf(
        Texture("Saudi plaster", bytes=Base64.getDecoder().decode(PLASTER)),
        Texture("Saudi limestone", bytes=Base64.getDecoder().decode(STONE)),
        Texture("Saudi timber", bytes=Base64.getDecoder().decode(WOOD)),
        Texture("Roof aggregate", bytes=Base64.getDecoder().decode(ROOF))
    )
}
