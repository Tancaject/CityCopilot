from pathlib import Path

from playwright.sync_api import expect, sync_playwright


ARTIFACT_DIR = Path(__file__).resolve().parent / "shop-type-filter"
ARTIFACT_DIR.mkdir(parents=True, exist_ok=True)


def fulfill_json(route, body):
    route.fulfill(status=200, content_type="application/json", body=body)


def main():
    discovery_urls = []

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1440, "height": 960})
        console_messages = []
        page.on("console", lambda msg: console_messages.append(f"{msg.type}: {msg.text}"))
        page.on("pageerror", lambda error: console_messages.append(f"pageerror: {error}"))
        page.add_init_script(
            """
            sessionStorage.setItem('citycopilot:selected-location', JSON.stringify({
              label: '当前位置',
              source: 'browser',
              point: { x: 112.997033, y: 28.115606 },
              updatedAt: Date.now()
            }));
            sessionStorage.setItem('citycopilot:auto-location-attempted', '1');
            """
        )
        page.route(
            "**/api/shop-type/list",
            lambda route: fulfill_json(
                route,
                """
                {
                  "success": true,
                  "data": [
                    { "id": 1, "name": "美食", "icon": "/icons/default-icon.png", "sort": 1 },
                    { "id": 2, "name": "美容", "icon": "/icons/default-icon.png", "sort": 2 },
                    { "id": 3, "name": "咖啡", "icon": "/icons/default-icon.png", "sort": 3 }
                  ]
                }
                """,
            ),
        )

        def discovery(route):
            discovery_urls.append(route.request.url)
            url = route.request.url
            if "typeId=2" in url:
                name = "初心美学"
                type_name = "生活服务;美容美发店"
            else:
                name = "瑞幸咖啡"
                type_name = "餐饮服务;咖啡厅"
            fulfill_json(
                route,
                f"""
                {{
                  "success": true,
                  "data": [
                    {{
                      "source": "amap",
                      "sourceId": "FILTER-1",
                      "orderable": false,
                      "typeName": "{type_name}",
                      "name": "{name}",
                      "area": "雨花区",
                      "address": "测试路 1 号",
                      "distance": 188,
                      "score": 45,
                      "avgPrice": 42
                    }}
                  ]
                }}
                """,
            )

        page.route("**/api/shop/discovery**", discovery)

        page.goto("http://localhost:5184/shops")
        page.wait_for_load_state("networkidle")

        expect(page.get_by_role("button", name="美食")).to_be_visible()
        expect(page.get_by_role("button", name="美容")).to_be_visible()
        expect(page.get_by_role("button", name="全部")).to_have_attribute("aria-pressed", "true")
        assert page.eval_on_selector(".shop-type-filter", "el => getComputedStyle(el).scrollbarWidth") == "none"

        page.get_by_role("button", name="美容").click()
        page.wait_for_url("**/shops?typeId=2&typeName=**")
        page.wait_for_function("() => document.body.innerText.includes('初心美学')")

        assert any("typeId=2" in url for url in discovery_urls), discovery_urls
        expect(page.get_by_role("button", name="美容")).to_have_attribute("aria-pressed", "true")
        expect(page.get_by_role("heading", name="美容")).to_be_visible()

        page.get_by_role("button", name="全部").click()
        page.wait_for_url("**/shops")
        assert "typeId=" not in page.url
        expect(page.get_by_role("button", name="全部")).to_have_attribute("aria-pressed", "true")

        (ARTIFACT_DIR / "page.html").write_text(page.content(), encoding="utf-8")
        (ARTIFACT_DIR / "console.txt").write_text("\n".join(console_messages), encoding="utf-8")
        (ARTIFACT_DIR / "discovery-urls.txt").write_text("\n".join(discovery_urls), encoding="utf-8")
        page.screenshot(path=str(ARTIFACT_DIR / "shop-type-filter.png"), full_page=True)
        browser.close()


if __name__ == "__main__":
    main()
