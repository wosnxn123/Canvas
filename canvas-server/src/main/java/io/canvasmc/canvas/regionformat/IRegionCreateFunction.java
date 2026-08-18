package io.canvasmc.canvas.regionformat;

import IRegionFile;

import java.io.IOException;

@FunctionalInterface
public interface IRegionCreateFunction {
    IRegionFile create(RegionCreatorInfo info) throws IOException;
}