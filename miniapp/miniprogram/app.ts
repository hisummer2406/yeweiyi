import { getToken } from './utils/storage';

interface IAppOption {
  globalData: {
    token: string;
  };
}

App<IAppOption>({
  globalData: {
    token: '',
  },
  onLaunch() {
    this.globalData.token = getToken();
  },
});
