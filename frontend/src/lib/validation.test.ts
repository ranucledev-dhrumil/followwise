import { describe, it, expect } from "vitest";
import { validatePassword, validateEmail, getUtf8ByteLength } from "./validation";

describe("validation", () => {
  describe("password byte length validation", () => {
    it("rejects 9 bytes", () => {
      const pwd9 = "a".repeat(9);
      expect(getUtf8ByteLength(pwd9)).toBe(9);
      expect(validatePassword(pwd9)).not.toBeNull();
    });

    it("accepts 10 bytes", () => {
      const pwd10 = "a".repeat(10);
      expect(getUtf8ByteLength(pwd10)).toBe(10);
      expect(validatePassword(pwd10)).toBeNull();
    });

    it("accepts 72 bytes", () => {
      const pwd72 = "a".repeat(72);
      expect(getUtf8ByteLength(pwd72)).toBe(72);
      expect(validatePassword(pwd72)).toBeNull();
    });

    it("rejects 73 bytes", () => {
      const pwd73 = "a".repeat(73);
      expect(getUtf8ByteLength(pwd73)).toBe(73);
      expect(validatePassword(pwd73)).not.toBeNull();
    });

    it("correctly handles multi-byte UTF-8 characters", () => {
      // '€' is 3 bytes in UTF-8
      // 3 of '€' = 9 bytes (should fail)
      const euro3 = "€€€";
      expect(getUtf8ByteLength(euro3)).toBe(9);
      expect(validatePassword(euro3)).not.toBeNull();

      // 4 of '€' = 12 bytes (should pass)
      const euro4 = "€€€€";
      expect(getUtf8ByteLength(euro4)).toBe(12);
      expect(validatePassword(euro4)).toBeNull();

      // 24 of '€' = 72 bytes (should pass)
      const euro24 = "€".repeat(24);
      expect(getUtf8ByteLength(euro24)).toBe(72);
      expect(validatePassword(euro24)).toBeNull();

      // 25 of '€' = 75 bytes (should fail)
      const euro25 = "€".repeat(25);
      expect(getUtf8ByteLength(euro25)).toBe(75);
      expect(validatePassword(euro25)).not.toBeNull();
    });
  });

  describe("email validation", () => {
    it("validates valid and invalid emails", () => {
      expect(validateEmail("user@example.com")).toBeNull();
      expect(validateEmail("")).toBe("Email is required");
      expect(validateEmail("invalid-email")).toBe("Invalid email address");
    });
  });
});
