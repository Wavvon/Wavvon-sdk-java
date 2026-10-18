package org.wavvon.sdk;

/**
 * The body of {@code PUT /me/app/profile}. Null fields are left out.
 *
 * @param name required
 * @param avatarUrl optional
 * @param description optional
 * @param webhookUrl https only (unless the hub allows private webhooks); receives slash commands
 *     and component interactions
 * @param homepageUrl optional
 * @param miniAppUrl optional; the mini-app players open
 * @param requiresCamera optional
 * @param game optional
 */
public record AppProfile(
    String name,
    String avatarUrl,
    String description,
    String webhookUrl,
    String homepageUrl,
    String miniAppUrl,
    Boolean requiresCamera,
    GameLaunchCard game) {

  /**
   * @param name the app's name
   * @return a profile with only a name
   */
  public static AppProfile named(String name) {
    return new AppProfile(name, null, null, null, null, null, null, null);
  }

  /**
   * @param url where the hub POSTs dispatches
   * @return a copy with {@code webhookUrl} replaced
   */
  public AppProfile withWebhookUrl(String url) {
    return new AppProfile(
        name, avatarUrl, description, url, homepageUrl, miniAppUrl, requiresCamera, game);
  }

  /**
   * @param text the description
   * @return a copy with {@code description} replaced
   */
  public AppProfile withDescription(String text) {
    return new AppProfile(
        name, avatarUrl, text, webhookUrl, homepageUrl, miniAppUrl, requiresCamera, game);
  }

  /**
   * @param url the avatar
   * @return a copy with {@code avatarUrl} replaced
   */
  public AppProfile withAvatarUrl(String url) {
    return new AppProfile(
        name, url, description, webhookUrl, homepageUrl, miniAppUrl, requiresCamera, game);
  }

  /**
   * @param url the mini-app's URL
   * @param camera whether it needs the camera
   * @return a copy with the mini-app replaced
   */
  public AppProfile withMiniApp(String url, boolean camera) {
    return new AppProfile(name, avatarUrl, description, webhookUrl, homepageUrl, url, camera, game);
  }

  /**
   * @param card the game card
   * @return a copy with {@code game} replaced
   */
  public AppProfile withGame(GameLaunchCard card) {
    return new AppProfile(
        name, avatarUrl, description, webhookUrl, homepageUrl, miniAppUrl, requiresCamera, card);
  }
}
