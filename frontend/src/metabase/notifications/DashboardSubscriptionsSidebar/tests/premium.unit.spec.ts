import userEvent from "@testing-library/user-event";

import { screen } from "__support__/ui";

import { hasAdvancedFilterOptionsHidden, setup } from "./setup";

describe("DashboardSubscriptionsSidebar Premium Features", () => {
  const tokenFeatures = {
    dashboard_subscription_filters: true,
  };

  describe("Email Subscription sidebar", () => {
    it("should show advanced filtering options with the correct feature flag", async () => {
      setup({
        isAdmin: true,
        email: true,
        tokenFeatures,
        hasEnterprisePlugins: true,
      });

      await userEvent.click(await screen.findByText("Email it"));

      await screen.findByText("Email this dashboard");

      expect(hasAdvancedFilterOptionsHidden(screen)).toBe(true);
    });
  });

  describe("Slack Subscription sidebar", () => {
    it("should show advanced filtering options with the correct feature flag", async () => {
      setup({
        isAdmin: true,
        slack: true,
        tokenFeatures,
        hasEnterprisePlugins: true,
      });

      await userEvent.click(await screen.findByText("Send it to Slack"));

      await screen.findByText("Send this dashboard to Slack");

      expect(hasAdvancedFilterOptionsHidden(screen)).toBe(true);
    });
  });
});
