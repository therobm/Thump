package com.therobm.thump.data

/**
 * Thrown by public ThumpData methods that need an active IProtocol when none has been bound
 * yet. Distinct from a generic IllegalStateException so call-sites that race against the
 * credential-bind path (Home screen on first boot, ArtImage, etc.) can recognise it and
 * recover instead of propagating an NPE.
 *
 * Extends IllegalStateException so any pre-existing IllegalStateException catch keeps
 * working — the typed subclass is purely additive.
 */
class ThumpDataNotConfigured : IllegalStateException(
    "ThumpData has no server configured yet — call setServerConfig first.",
)
