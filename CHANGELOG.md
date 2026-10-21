# Changelog

## 0.2.0

- Rivium user token: one signed token for every Rivium product.
  `RiviumStorage.builder(apiKey).userTokenProvider { ... }.build()` (or the
  `userTokenProvider` / `userToken` constructor parameters), `userTokens`
  (`provider`, `set`, `refresh`, `invalidate`, same API as Rivium Sync), and
  `setUserToken`, `setUserTokenProvider`, `refreshUserToken`.
- The token is sent as `x-user-token`. The provider is asked before the first
  request and once more, with one retry of the request (uploads included),
  when the server answers `token_expired` or `token_invalid`. Concurrent
  requests share one provider call. A token about to expire is refreshed
  before it is sent.
- New `RiviumStorageException.UserTokenRequiredException` (`token_required`)
  and `RiviumStorageException.UserTokenRejectedException` (`code`). Code with
  an exhaustive `when` over `RiviumStorageException` needs the two branches.
- While a token is in use, `x-user-id` is not sent. `userId` / `setUserId`
  still work as before (legacy, unverifiable).

## 0.1.0

- Initial release
