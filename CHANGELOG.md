# Changelog

## Unreleased

- Every request to Rivium Storage now names the SDK in an `X-Rivium-SDK:
  android/<version>` header (only the public SDK name and version), like the
  other Rivium SDKs. New constants `RiviumStorage.SDK_NAME` and
  `RiviumStorage.SDK_VERSION`; the version comes from `VERSION_NAME` in
  `gradle.properties`, the same value as the Maven version.

## 0.2.0

- Sign users in with a Rivium user token — the same token Rivium Sync, Chat,
  Push and A/B Testing accept (a Rivium Auth sign-in token works as is):
  `RiviumStorage.builder(apiKey).userTokenProvider { ... }.build()`, or
  `setUserToken` / `refreshUserToken` and `userTokens` (same API as Rivium
  Sync). The SDK sends the token with every request and gets a new one when it
  expires, so your access rules see a verified user.
- New `RiviumStorageException.UserTokenRequiredException` and
  `RiviumStorageException.UserTokenRejectedException`. If your code uses an
  exhaustive `when` over `RiviumStorageException`, add the two branches.
- `userId` / `setUserId` still work but are deprecated: the server can't
  verify a user ID sent by an app. Use the token provider.

## 0.1.0

- Initial release
