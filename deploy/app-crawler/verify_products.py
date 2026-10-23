"""Bounded live acceptance through the authenticated API. Never log raw materials."""
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
import uuid


class VerificationFailed(RuntimeError):
    pass


class Api:
    def __init__(self, config):
        self.base = config['baseUrl'].rstrip('/')
        if not self.base.startswith('https://'):
            raise VerificationFailed('HTTPS_API_REQUIRED')
        self.key = config['apiKey']

    def call(self, path, payload=None, idempotency=None):
        headers = {'Authorization': 'Bearer ' + self.key}
        if payload is not None:
            headers['Content-Type'] = 'application/json'
        if idempotency:
            headers['Idempotency-Key'] = idempotency
        request = urllib.request.Request(self.base + path,
            data=None if payload is None else json.dumps(payload).encode(), headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=150) as response:
                body = json.load(response)
        except urllib.error.HTTPError as error:
            # URLs and response bodies may contain materials/credentials: emit neither.
            raise VerificationFailed('HTTP_' + str(error.code)) from None
        except Exception:
            raise VerificationFailed('API_UNREACHABLE') from None
        if body.get('success') is not True:
            raise VerificationFailed('API_REJECTED')
        return body.get('data')

    def task(self, action, request):
        submitted = self.call('/tasks', {'action': action, 'request': request}, str(uuid.uuid4()))
        task_id = str(submitted['taskId'])
        if not task_id.isdigit():
            raise VerificationFailed('INVALID_TASK_ID')
        finished = False
        try:
            deadline = time.monotonic() + 660
            while time.monotonic() < deadline:
                status = self.call('/tasks/' + task_id)
                state = status['status']
                if state == 'SUCCEEDED':
                    finished = True
                    result = self.call('/tasks/' + task_id + '/result')
                    if result.get('status') != 'SUCCESS':
                        raise VerificationFailed('BUSINESS_NOT_SUCCESS')
                    return result
                if state == 'WAITING_USER':
                    raise VerificationFailed('LOGIN_OR_MANUAL_ACTION_REQUIRED')
                if state in ('FAILED', 'TIMED_OUT', 'CANCELLED'):
                    finished = True
                    raise VerificationFailed('TASK_' + state)
                time.sleep(3)
            raise VerificationFailed('TASK_DEADLINE')
        finally:
            if not finished:
                try:
                    self.call('/tasks/' + task_id + '/cancel', {})
                except VerificationFailed:
                    pass


def verify_platform(api, platform):
    search = platform + 'UnionSearchProducts'
    products = api.task(search, {'keyword': '抽纸', 'maxResults': 2}).get('items', [])
    if not products:
        raise VerificationFailed('NO_PRODUCTS')
    sample = products[0]
    material = sample.get('encryptedProductId') or sample.get('productId')
    if not material:
        raise VerificationFailed('NO_PRODUCT_IDENTITY')
    promotion = api.task('getPromotionUrl' if platform == 'jd' else 'getTbPromotionUrl',
                         {'keyword': material, 'adSiteName': '0'})
    product_id = promotion.get('productId', '')
    if promotion.get('identityResolved') is not True or not re.fullmatch(r'[0-9]+', product_id):
        raise VerificationFailed('NUMERIC_ID_UNRESOLVED')
    # Re-submit the same material: exercises the encrypted-ID cache and numeric queue boundary.
    numeric = api.task(search, {'keyword': material, 'maxResults': 2})
    candidates = numeric.get('items', [])
    if not candidates:
        raise VerificationFailed('CACHED_ID_SEARCH_EMPTY')
    explicit = api.task(search, {'keyword': product_id, 'maxResults': 2})
    if not explicit.get('items'):
        raise VerificationFailed('NUMERIC_SEARCH_EMPTY')
    # Search cards can still be encrypted. Confirm an exact numeric match through promotion.
    repeated = api.task('getPromotionUrl' if platform == 'jd' else 'getTbPromotionUrl',
                        {'keyword': product_id, 'adSiteName': '0'})
    if repeated.get('productId') != product_id or repeated.get('identityResolved') is not True:
        raise VerificationFailed('NUMERIC_SEARCH_MISMATCH')
    watches = api.call('/price-watches')
    watch = next((row for row in watches if row.get('platform') == platform
                  and row.get('productId') == product_id), None)
    if watch is None:
        raise VerificationFailed('WATCH_NOT_INGESTED')
    if any(field in watch for field in ('encryptedProductId', 'unionProductId', 'promotionUrl')):
        raise VerificationFailed('WATCH_MATERIAL_LEAK')
    snapshots = api.call('/price-watches/' + str(watch['id']) + '/snapshots')
    if not snapshots:
        raise VerificationFailed('PRICE_NOT_INGESTED')
    raw_price = str(promotion.get('productInfo', {}).get('price', ''))
    price = raw_price if re.fullmatch(r'[0-9]+(?:\.[0-9]+)?', raw_price) else 'recorded'
    print(f'PRODUCT_VERIFY_OK platform={platform} productId={product_id} price={price}', flush=True)


def main():
    config = json.loads(os.environ['RX_MIMIC_SMOKE_ACCESS'])
    api = Api(config)
    api.call('/price-watches/budget')
    failed = False
    for platform in ('jd', 'tb'):
        try:
            verify_platform(api, platform)
        except VerificationFailed as error:
            failed = True
            print(f'PRODUCT_VERIFY_FAILED platform={platform} code={error}', flush=True)
    return 1 if failed else 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception:
        print('PRODUCT_VERIFY_FAILED code=CONFIG_OR_PROTOCOL_ERROR', flush=True)
        sys.exit(1)
