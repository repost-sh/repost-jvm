"use strict";

class FixtureProtocolError extends Error {
  constructor(code, message, statusCode = 400) {
    super(message);
    this.name = "FixtureProtocolError";
    this.code = code;
    this.statusCode = statusCode;
  }
}

function protocolError(code, message, statusCode) {
  return new FixtureProtocolError(code, message, statusCode);
}


function integer(value, minimum, label) {
  if (!Number.isSafeInteger(value) || value < minimum) {
    throw protocolError("INVALID_CONTRACT", `${label} must be an integer >= ${minimum}.`);
  }
  return value;
}

function bodyBuffer(recipe, assets, seen = new Set()) {
  if (recipe === null || typeof recipe !== "object" || Array.isArray(recipe)) {
    throw protocolError("INVALID_BODY_RECIPE", "A fixture body recipe must be an object.");
  }
  if (recipe.kind === undefined && typeof recipe.asset === "string") {
    if (!Object.prototype.hasOwnProperty.call(assets, recipe.asset)) {
      throw protocolError("INVALID_BODY_RECIPE", "A fixture body references an unknown asset.");
    }
    if (seen.has(recipe.asset)) {
      throw protocolError("INVALID_BODY_RECIPE", "A fixture body asset cycle is not allowed.");
    }
    const nextSeen = new Set(seen);
    nextSeen.add(recipe.asset);
    return bodyBuffer(assets[recipe.asset], assets, nextSeen);
  }

  switch (recipe.kind) {
    case "base64": {
      if (typeof recipe.data !== "string") {
        throw protocolError("INVALID_BODY_RECIPE", "A base64 body requires string data.");
      }
      const decoded = Buffer.from(recipe.data, "base64");
      if (decoded.toString("base64") !== recipe.data) {
        throw protocolError("INVALID_BODY_RECIPE", "A base64 body is not canonical RFC 4648 data.");
      }
      return decoded;
    }
    case "repeat": {
      integer(recipe.byte, 0, "repeat.byte");
      integer(recipe.length, 0, "repeat.length");
      if (recipe.byte > 255) {
        throw protocolError("INVALID_BODY_RECIPE", "repeat.byte must be <= 255.");
      }
      return Buffer.alloc(recipe.length, recipe.byte);
    }
    case "paddedAsset": {
      integer(recipe.totalLength, 0, "paddedAsset.totalLength");
      const source = bodyBuffer({ asset: recipe.asset }, assets, seen);
      if (source.length > recipe.totalLength) {
        throw protocolError("INVALID_BODY_RECIPE", "paddedAsset totalLength is smaller than its asset.");
      }
      return Buffer.concat([
        source,
        Buffer.alloc(recipe.totalLength - source.length, 0x20),
      ]);
    }
    case "gzipStored":
      return gzipStored(bodyBuffer(recipe.source, assets, seen));
    case "concatenated": {
      if (!Array.isArray(recipe.members) || recipe.members.length === 0) {
        throw protocolError("INVALID_BODY_RECIPE", "A concatenated body requires members.");
      }
      return Buffer.concat(recipe.members.map((member) => {
        if (typeof member === "string") return bodyBuffer({ asset: member }, assets, seen);
        return bodyBuffer(member, assets, seen);
      }));
    }
    case "jsonRecipe": {
      integer(recipe.count, 0, "jsonRecipe.count");
      if (recipe.shape === "nestedArrays") {
        return Buffer.from(`${"[".repeat(recipe.count)}0${"]".repeat(recipe.count)}`, "utf8");
      }
      if (recipe.shape === "flatObject") {
        return Buffer.from(`{${Array.from({ length: recipe.count }, (_, index) => `"k${index}":0`).join(",")}}`, "utf8");
      }
      throw protocolError("INVALID_BODY_RECIPE", "A jsonRecipe shape must be nestedArrays or flatObject.");
    }
    default:
      throw protocolError("INVALID_BODY_RECIPE", "The fixture body kind is unsupported.");
  }
}

let crcTable;
function crc32(buffer) {
  if (!crcTable) {
    crcTable = Array.from({ length: 256 }, (_, index) => {
      let value = index;
      for (let bit = 0; bit < 8; bit += 1) {
        value = (value & 1) ? (0xedb88320 ^ (value >>> 1)) : (value >>> 1);
      }
      return value >>> 0;
    });
  }
  let crc = 0xffffffff;
  for (const byte of buffer) crc = crcTable[(crc ^ byte) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

function gzipStored(source) {
  const blocks = [];
  if (source.length === 0) blocks.push(Buffer.from([1, 0, 0, 255, 255]));
  for (let offset = 0; offset < source.length; offset += 65535) {
    const length = Math.min(65535, source.length - offset);
    const header = Buffer.alloc(5);
    header[0] = offset + length === source.length ? 1 : 0;
    header.writeUInt16LE(length, 1);
    header.writeUInt16LE((~length) & 0xffff, 3);
    blocks.push(header, source.subarray(offset, offset + length));
  }
  const trailer = Buffer.alloc(8);
  trailer.writeUInt32LE(crc32(source), 0);
  trailer.writeUInt32LE(source.length >>> 0, 4);
  return Buffer.concat([
    Buffer.from([0x1f, 0x8b, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xff]),
    ...blocks,
    trailer,
  ]);
}

module.exports = {
  FixtureProtocolError,
  bodyBuffer,
  gzipStored,
  integer,
  protocolError,
};
