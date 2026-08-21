import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../../pages/WasmWorkspacePage";
import {
  createToken,
} from "./helpers";

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
});

test("File with tokens is its own tokens source", async ({ page }) => {
  await WasmWorkspacePage.mockConfigFlags(page, ["enable-token-lib-sync"]);
  const workspacePage = new WasmWorkspacePage(page);

  await workspacePage.setupEmptyFile(page);

  await workspacePage.mockRPC(
    "get-team-shared-files?team-id=*",
    "workspace/get-team-shared-libraries-non-empty.json",
  );

  await workspacePage.goToWorkspace();

  // A file without tokens is not tokens source
  await workspacePage.clickAssets();
  await workspacePage.openLibrariesModal();
  await workspacePage.librariesModal.getByRole("tab", { name: "This file" }).click();

  await expect(
    workspacePage.librariesModal.getByText(
      "Tokens source",
    ),
  ).not.toBeVisible();

  await workspacePage.closeLibrariesModal();

  // Create a token in the file
  await workspacePage.clickTokens();
  await createToken(
    page,
    "Color",
    "color.primary",
    "Value",
    "textbox",
    "#ff0000",
  );

  // Now it is a tokens source
  await workspacePage.clickAssets();
  await workspacePage.openLibrariesModal();
  await workspacePage.librariesModal.getByRole("tab", { name: "This file" }).click();

  await expect(
    workspacePage.librariesModal.getByText(
      "Tokens source",
    ),
  ).toBeVisible();

});
