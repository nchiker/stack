package org.stackdocs.app;

/**
 * The viewer's own isolated process (`:render`): the same code as {@link ExtractService}, so that drawing a page for the person never waits for, or is
 * killed with, the text reading of the folder files (which runs in `:extract`).
 */
public final class RenderService extends ExtractService {}
