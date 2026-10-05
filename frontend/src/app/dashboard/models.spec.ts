import { accountMask, accountName } from './models';

describe('account labels', () => {
  it('masks an account number to its last four digits', () => {
    expect(accountMask('ACC-00004821')).toBe('4821');
  });

  it('names an account from its mask', () => {
    expect(accountName('ACC-00004821')).toBe('Brokerage ··4821');
  });
});
