package com.stash.feature.nowplaying.canvas

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Now Playing's Canvas seams: the Spotify data-layer resolver and the system network gate. Both are
 * process singletons, like the client they wrap.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class CanvasVideoModule {

    @Binds
    abstract fun bindCanvasVideoResolver(impl: SpotifyCanvasVideoResolver): CanvasVideoResolver

    @Binds
    abstract fun bindCanvasNetworkGate(impl: SystemCanvasNetworkGate): CanvasNetworkGate
}
