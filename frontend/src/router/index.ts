import { createRouter, createWebHistory } from 'vue-router'
import HomePage from '../pages/HomePage.vue'

const UserLoginPage = () => import('@/pages/user/UserLoginPage.vue')
const UserRegisterPage = () => import('@/pages/user/UserRegisterPage.vue')
const UserManagePage = () => import('@/pages/admin/UserManagePage.vue')
const AddPicturePage = () => import('@/pages/AddPicturePage.vue')
const PictureManagePage = () => import('@/pages/admin/PictureManagePage.vue')
const PictureDetailPage = () => import('@/pages/PictureDetailPage.vue')
const AddPictureBatchPage = () => import('@/pages/AddPictureBatchPage.vue')
const SpaceManagePage = () => import('@/pages/admin/SpaceManagePage.vue')
const AddSpacePage = () => import('@/pages/AddSpacePage.vue')
const MySpacePage = () => import('@/pages/MySpacePage.vue')
const SpaceDetailPage = () => import('@/pages/SpaceDetailPage.vue')
const SearchPicturePage = () => import('@/pages/SearchPicturePage.vue')
const SpaceAnalyzePage = () => import('@/pages/SpaceAnalyzePage.vue')
const SpaceUserManagePage = () => import('@/pages/admin/SpaceUserManagePage.vue')

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomePage,
    },
    {
      path: '/user/login',
      name: '用户登录',
      component: UserLoginPage,
    },
    {
      path: '/user/register',
      name: '用户注册',
      component: UserRegisterPage,
    },
    {
      path: '/admin/userManage',
      name: '用户管理',
      component: UserManagePage,
    },
    {
      path: '/add_picture',
      name: '创建图片',
      component: AddPicturePage,
    },
    {
      path: '/picture/:id',
      name: '图片详情',
      component: PictureDetailPage,
      props: true,
    },
    {
      path: '/add_picture/batch',
      name: '批量创建图片',
      component: AddPictureBatchPage,
    },
    {
      path: '/admin/pictureManage',
      name: '图片管理',
      component: PictureManagePage,
    },
    {
      path: '/admin/spaceManage',
      name: '空间管理',
      component: SpaceManagePage,
    },
    {
      path: '/add_space',
      name: '创建空间',
      component: AddSpacePage,
    },
    {
      path: '/my_space',
      name: '我的空间',
      component: MySpacePage,
    },
    {
      path: '/space/:id',
      name: '空间详情',
      component: SpaceDetailPage,
      props: true,
    },
    {
      path: '/search_picture',
      name: '搜索图片',
      component: SearchPicturePage,
      props: true,
    },
    {
      path: '/space_analyze',
      name: '空间分析',
      component: SpaceAnalyzePage,
    },
    {
      path: '/spaceUserManage/:id',
      name: '空间成员管理',
      component: SpaceUserManagePage,
      props: true,
    },
  ],
})

export default router
