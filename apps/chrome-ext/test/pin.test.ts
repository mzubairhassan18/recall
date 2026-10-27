import { describe, expect, it } from "vitest";
import { KEY_BYTES } from "@recall/crypto-core";
import {
  PIN_BASE_LOCKOUT_MS,
  PIN_MAX_LOCKOUT_MS,
  createPinWrap,
  isValidPin,
  openPinWrap,
  pinLockoutMs,
} from "../src/lib/pin.js";

const KEY = new Uint8Array(KEY_BYTES).fill(7);

describe("PIN wrapping", () => {
  it("wraps and unwraps the vault key without storing the PIN", async () => {
    const wrap = await createPinWrap("1234", KEY, 1);
    expect(wrap.salt).not.toBe("");
    expect(wrap.iv).not.toBe("");
    expect(wrap.ct).not.toBe("");
    expect(JSON.stringify(wrap)).not.toContain("1234");

    const opened = await openPinWrap("1234", wrap, 1);
    expect(opened).toEqual(KEY);
    opened.fill(0);
  });

  it("uses a fresh salt and IV for every wrap", async () => {
    const a = await createPinWrap("1234", KEY, 1);
    const b = await createPinWrap("1234", KEY, 1);
    expect(a.salt).not.toBe(b.salt);
    expect(a.iv).not.toBe(b.iv);
    expect(a.ct).not.toBe(b.ct);
  });

  it("rejects a wrong PIN", async () => {
    const wrap = await createPinWrap("1234", KEY, 1);
    await expect(openPinWrap("4321", wrap, 1)).rejects.toThrow("wrong-pin");
  });

  it("rejects a tampered wrap", async () => {
    const wrap = await createPinWrap("1234", KEY, 1);
    const tampered = { ...wrap, ct: `${wrap.ct.slice(0, -4)}AAAA` };
    await expect(openPinWrap("1234", tampered, 1)).rejects.toThrow("wrong-pin");
  });

  it("accepts only four digits", () => {
    expect(isValidPin("1234")).toBe(true);
    expect(isValidPin("0123")).toBe(true);
    expect(isValidPin("123")).toBe(false);
    expect(isValidPin("12345")).toBe(false);
    expect(isValidPin("12a4")).toBe(false);
    expect(isValidPin(1234)).toBe(false);
  });

  it("locks after repeated failures and backs off", () => {
    expect(pinLockoutMs(0)).toBe(0);
    expect(pinLockoutMs(4)).toBe(0);
    expect(pinLockoutMs(5)).toBe(PIN_BASE_LOCKOUT_MS);
    expect(pinLockoutMs(6)).toBe(PIN_BASE_LOCKOUT_MS * 2);
    expect(pinLockoutMs(20)).toBe(PIN_MAX_LOCKOUT_MS);
  });
});
