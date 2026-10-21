import { accountMask, accountName } from './models';

describe('account labels', () => {
  it('masks an account number to its last four digits', () => {
    expect(accountMask('ACC-00004821')).toBe('4821');
  });

  it('names an account by its place among the client\'s accounts', () => {
    expect(accountName('ACC-00004821', 2)).toBe('Brokerage 2');
  });

  it('falls back to the mask for an account the API has not numbered', () => {
    expect(accountName('ACC-00004821')).toBe('Brokerage ··4821');
    expect(accountName('ACC-00004821', null)).toBe('Brokerage ··4821');
  });
});
